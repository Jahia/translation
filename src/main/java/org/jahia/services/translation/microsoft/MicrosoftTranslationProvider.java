/**
 * ==========================================================================================
 * =                   JAHIA'S DUAL LICENSING - IMPORTANT INFORMATION                       =
 * ==========================================================================================
 *
 *                                 http://www.jahia.com
 *
 *     Copyright (C) 2002-2020 Jahia Solutions Group SA. All rights reserved.
 *
 *     THIS FILE IS AVAILABLE UNDER TWO DIFFERENT LICENSES:
 *     1/GPL OR 2/JSEL
 *
 *     1/ GPL
 *     ==================================================================================
 *
 *     IF YOU DECIDE TO CHOOSE THE GPL LICENSE, YOU MUST COMPLY WITH THE FOLLOWING TERMS:
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 *
 *     2/ JSEL - Commercial and Supported Versions of the program
 *     ===================================================================================
 *
 *     IF YOU DECIDE TO CHOOSE THE JSEL LICENSE, YOU MUST COMPLY WITH THE FOLLOWING TERMS:
 *
 *     Alternatively, commercial and supported versions of the program - also known as
 *     Enterprise Distributions - must be used in accordance with the terms and conditions
 *     contained in a separate written agreement between you and Jahia Solutions Group SA.
 *
 *     If you are unsure which license is appropriate for your use,
 *     please contact the sales department at sales@jahia.com.
 */
package org.jahia.services.translation.microsoft;

import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.lang.StringEscapeUtils;
import org.jahia.data.templates.JahiaTemplatesPackage;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.notification.HttpClientService;
import org.jahia.services.templates.JahiaModuleAware;
import org.jahia.services.translation.AbstractTranslationProvider;
import org.jahia.services.translation.TranslationException;
import org.jahia.utils.i18n.Messages;
import org.jahia.utils.i18n.ResourceBundles;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.jcr.RepositoryException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.util.*;

/**
 * Translation provider for Microsoft Translator.
 * See {@link org.jahia.services.translation.TranslationService}.
 */
public class MicrosoftTranslationProvider extends AbstractTranslationProvider implements JahiaModuleAware {

    private transient static Logger logger = org.slf4j.LoggerFactory.getLogger(MicrosoftTranslationProvider.class);

    private HttpClientService httpClientService;
    private String accessTokenUrl;
    private String translateUrl;
    private String translateArrayUrl;
    private JahiaTemplatesPackage module;

    private Map<String, MicrosoftTranslatorAccess> accesses = new HashMap<String, MicrosoftTranslatorAccess>();

    @Override
    public void setJahiaModule(JahiaTemplatesPackage module) {
        this.module = module;
    }

    private String authenticate(JCRSiteNode site, Locale uiLocale) throws TranslationException {
        String clientId = null;
        String clientSecret = null;
        try {
            if (site.isNodeType("jmix:microsoftTranslatorSettings")) {
                clientId = site.getPropertyAsString("j:microsoftClientId");
                clientSecret = site.getPropertyAsString("j:microsoftClientSecret");
            }
        } catch (RepositoryException e) {
            throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToGetCredentials",uiLocale));
        }
        String key = clientId + clientSecret;
        String accessToken;
        if (!accesses.containsKey(key) || accesses.get(key).getExpiration() < System.currentTimeMillis()) {
            PostMethod method = new PostMethod(accessTokenUrl);
            method.addParameter("grant_type", "client_credentials");
            method.addParameter("client_id", clientId);
            method.addParameter("client_secret", clientSecret);
            method.addParameter("scope", "http://api.microsofttranslator.com");
            int returnCode;
            String bodyAsString;
            long callTime = System.currentTimeMillis();
            try {
                returnCode = httpClientService.getHttpClient(accessTokenUrl).executeMethod(method);
                bodyAsString = method.getResponseBodyAsString();
                if (returnCode != HttpStatus.SC_OK) {
                    throw new TranslationException(Messages.getWithArgs(ResourceBundles.get(module, uiLocale),
                            "siteSettings.translation.microsoft.errorWithCode", returnCode), bodyAsString);
                }
            } catch (IOException e) {
                throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToCallService",uiLocale));
            } finally {
                method.releaseConnection();
            }
            try {
                JSONObject jsonObject = new JSONObject(bodyAsString);
                accessToken = jsonObject.getString("access_token");
                accesses.put(key, new MicrosoftTranslatorAccess(accessToken,
                        callTime + (jsonObject.getLong("expires_in") - 1) * 1000)); // substract 1 second to expiration time for execution time
            } catch (JSONException e) {
                throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToParse",uiLocale));
            }
        } else {
            accessToken = accesses.get(key).getToken();
        }
        return accessToken;
    }

    /**
     * Calls the Microsoft Translator Translate GET method to translate a single text.
     *
     *
     * @param text a text to translate
     * @param srcLanguage the source language code
     * @param destLanguage the destination language code
     * @param isHtml is the text html or plain text
     * @param site the site
     * @param uiLocale
     * @return the translated text
     * @throws TranslationException
     */
    public String translate(String text, String srcLanguage, String destLanguage, boolean isHtml, JCRSiteNode site, Locale uiLocale) throws TranslationException {
        String accessToken = authenticate(site, uiLocale);
        if (accessToken == null) {
            throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToAuthenticate",uiLocale));
        }
        GetMethod method = new GetMethod(translateUrl);
        method.setRequestHeader("Authorization", "Bearer " + accessToken);
        method.setQueryString(new NameValuePair[]{
                new NameValuePair("text", text),
                new NameValuePair("from", srcLanguage),
                new NameValuePair("to", destLanguage),
                new NameValuePair("contentType", isHtml ? "text/html" : "text/plain")
        });
        int returnCode;
        String translatedText;
        try {
            try {
                returnCode = httpClientService.getHttpClient(translateUrl).executeMethod(method);
            } catch (Exception e) {
                throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToCallService",uiLocale));
            }
            if (returnCode != HttpStatus.SC_OK) {
                throw new TranslationException(Messages.getWithArgs(ResourceBundles.get(module, uiLocale),
                        "siteSettings.translation.microsoft.errorWithCode", returnCode),
                        getResponseBodyAsStringQuietly(method));
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(method.getResponseBodyAsStream());
                translatedText = document.getElementsByTagName("string").item(0).getTextContent();
            } catch (Exception e) {
                throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToParse",uiLocale));
            }
        } finally {
            method.releaseConnection();
        }
        return translatedText;
    }

    private String getResponseBodyAsStringQuietly(HttpMethodBase method) {
        try {
            return method.getResponseBodyAsString();
        } catch (IOException e) {
            if (logger.isDebugEnabled()) {
                logger.warn("Unable to get response body as string", e);
            }
        }
        return null;
    }

    /**
     * Calls the Microsoft Translator TranslateArray POST method to translate several texts at one.
     *
     *
     * @param texts a list of texts to translate
     * @param srcLanguage the source language code
     * @param destLanguage the destination language code
     * @param isHtml are the texts html or plain texts
     * @param site the site
     * @param uiLocale
     * @return the translated texts
     * @throws TranslationException
     */
    public List<String> translate(List<String> texts, String srcLanguage, String destLanguage, boolean isHtml, JCRSiteNode site, Locale uiLocale) throws TranslationException {
        String accessToken = authenticate(site, uiLocale);
        if (accessToken == null) {
            throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToAuthenticate",uiLocale));
        }
        PostMethod method = new PostMethod(translateArrayUrl);
        method.setRequestHeader("Authorization", "Bearer " + accessToken);
        StringBuilder body = new StringBuilder();
        body.append("<TranslateArrayRequest>" +
                "<AppId />" +
                "<From>" + srcLanguage + "</From>" +
                "<Options>" +
                "<Category xmlns=\"http://schemas.datacontract.org/2004/07/Microsoft.MT.Web.Service.V2\" />" +
                "<ContentType xmlns=\"http://schemas.datacontract.org/2004/07/Microsoft.MT.Web.Service.V2\">" + (isHtml ? "text/html" : "text/plain") + "</ContentType>" +
                "<ReservedFlags xmlns=\"http://schemas.datacontract.org/2004/07/Microsoft.MT.Web.Service.V2\" />" +
                "<State xmlns=\"http://schemas.datacontract.org/2004/07/Microsoft.MT.Web.Service.V2\" />" +
                "<Uri xmlns=\"http://schemas.datacontract.org/2004/07/Microsoft.MT.Web.Service.V2\" />" +
                "<User xmlns=\"http://schemas.datacontract.org/2004/07/Microsoft.MT.Web.Service.V2\" />" +
                "</Options>" +
                "<Texts>");
        for (String text : texts) {
            body.append("<string xmlns=\"http://schemas.microsoft.com/2003/10/Serialization/Arrays\">" + (isHtml ? StringEscapeUtils.escapeHtml(text) : text) + "</string>");
        }
        body.append("</Texts>" +
                "<To>" + destLanguage + "</To>" +
                "</TranslateArrayRequest>");
        int returnCode;
        List<String> translatedTexts = new ArrayList<String>();
        try {
            try {
                method.setRequestEntity(new StringRequestEntity(body.toString(), "text/xml", "UTF-8"));
                returnCode = httpClientService.getHttpClient(translateArrayUrl).executeMethod(method);
            } catch (Exception e) {
                throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToCallService",uiLocale));
            }
            if (returnCode != HttpStatus.SC_OK) {
                throw new TranslationException(Messages.getWithArgs(ResourceBundles.get(module, uiLocale),
                        "siteSettings.translation.microsoft.errorWithCode", returnCode),
                        getResponseBodyAsStringQuietly(method));
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(method.getResponseBodyAsStream());
                NodeList nodes = document.getElementsByTagName("TranslatedText");
                for (int i = 0; i < nodes.getLength(); i++) {
                    translatedTexts.add(nodes.item(i).getTextContent());
                }
            } catch (Exception e) {
                throw new TranslationException(Messages.get(module,"siteSettings.translation.microsoft.failedToParse",uiLocale));
            }
        } finally {
            method.releaseConnection();
        }
        return translatedTexts;
    }

    /**
     * Checks if Microsoft Translator is enabled for a given site.
     *
     * @param site a site
     * @return a boolean
     */
    public boolean isEnabled(JCRSiteNode site) {
        try {
            return site.isNodeType("jmix:microsoftTranslatorSettings") && site.hasProperty("j:microsoftTranslationActivated") && site.getProperty("j:microsoftTranslationActivated").getBoolean();
        } catch (RepositoryException e) {
            logger.error("Failed to check if Microsoft Translator provider is enabled", e);
        }
        return false;
    }

    public void setHttpClientService(HttpClientService httpClientService) {
        this.httpClientService = httpClientService;
    }

    public void setAccessTokenUrl(String accessTokenUrl) {
        this.accessTokenUrl = accessTokenUrl;
    }

    public void setTranslateUrl(String translateUrl) {
        this.translateUrl = translateUrl;
    }

    public void setTranslateArrayUrl(String translateArrayUrl) {
        this.translateArrayUrl = translateArrayUrl;
    }

    private class MicrosoftTranslatorAccess {

        private String token;
        private long expiration;

        private MicrosoftTranslatorAccess(String token, long expiration) {
            this.token = token;
            this.expiration = expiration;
        }

        public String getToken() {
            return token;
        }

        public long getExpiration() {
            return expiration;
        }

    }

}
