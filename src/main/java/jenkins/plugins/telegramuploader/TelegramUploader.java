/*
 * The MIT License
 *
 * Copyright (c) 2017 Victor Antonovich <v.antonovich@gmail.com>
 *
 * ----------------------------------------------------------------------
 * Permission  is hereby granted, free of charge, to any person obtaining
 * a  copy  of  this  software  and  associated  documentation files (the
 * "Software"),  to  deal  in the Software without restriction, including
 * without  limitation  the  rights to use, copy, modify, merge, publish,
 * distribute,  sublicense,  and/or  sell  copies of the Software, and to
 * permit  persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this  permission  notice  shall be
 * included  in  all  copies  or  substantial  portions  of the Software.
 *                        -----------------------
 * THE  SOFTWARE  IS  PROVIDED  "AS  IS",  WITHOUT  WARRANTY OF ANY KIND,
 * EXPRESS  OR  IMPLIED,  INCLUDING  BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN  NO  EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM,  DAMAGES  OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT  OR  OTHERWISE,  ARISING  FROM,  OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE   OR   THE   USE   OR   OTHER   DEALINGS  IN  THE  SOFTWARE.
 */

package jenkins.plugins.telegramuploader;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nonnull;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import jenkins.util.VirtualFile;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * @author Victor Antonovich (v.antonovich@gmail.com)
 */
public class TelegramUploader extends Notifier implements SimpleBuildStep {
    // 50 MB file size limit for sendDocument bot method
    // (https://core.telegram.org/bots/api#sending-files)
    private final static long SEND_FILE_SIZE_LIMIT = 50 * 1024 * 1024;

    // sendDocument method document caption size limit
    // (https://core.telegram.org/bots/api#senddocument)
    private final static int SEND_DOCUMENT_CAPTION_SIZE_LIMIT = 1024;

    // Environment variable name for changelog substitution
    private final static String ENV_VAR_CHANGELOG_NAME = "TELEGRAM_UPLOADER_CHANGELOG";

    private String chatId;
    private String forwardChatIds;
    private String caption;
    private String filter;
    private boolean silent;
    private boolean failBuildIfUploadFailed;
    private boolean sendLinkIfUploadSizeLimitExceeded;
    private boolean onlySendLink;

    @DataBoundConstructor
    public TelegramUploader(String chatId, String filter) {
        this.chatId = chatId;
        this.filter = filter;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getForwardChatIds() {
        return forwardChatIds;
    }

    @DataBoundSetter
    public void setForwardChatIds(String forwardChatIds) {
        this.forwardChatIds = forwardChatIds;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public String getCaption() {
        return caption;
    }

    @DataBoundSetter
    public void setCaption(String caption) {
        this.caption = caption;
    }

    public boolean isSilent() {
        return silent;
    }

    @DataBoundSetter
    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    public boolean isFailBuildIfUploadFailed() {
        return failBuildIfUploadFailed;
    }

    @DataBoundSetter
    public void setFailBuildIfUploadFailed(boolean failBuildIfUploadFailed) {
        this.failBuildIfUploadFailed = failBuildIfUploadFailed;
    }

    public boolean isSendLinkIfUploadSizeLimitExceeded() {
        return sendLinkIfUploadSizeLimitExceeded;
    }

    @DataBoundSetter
    public void setSendLinkIfUploadSizeLimitExceeded(boolean sendLinkIfUploadSizeLimitExceeded) {
        this.sendLinkIfUploadSizeLimitExceeded = sendLinkIfUploadSizeLimitExceeded;
    }

    public boolean isOnlySendLink() {
        return onlySendLink;
    }

    @DataBoundSetter
    public void setOnlySendLink(boolean onlySendLink) {
        this.onlySendLink = onlySendLink;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public TelegramUploaderDescriptor getDescriptor() {
        return (TelegramUploaderDescriptor) super.getDescriptor();
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();

        Result buildResult = build.getResult();

        if (buildResult != null && buildResult.isWorseOrEqualTo(Result.FAILURE)) {
            logger.println("Skipping artifacts uploading to the Telegram because of build failure");
            return;
        }

        String artifactsGlob = Util.fixEmptyAndTrim(this.filter);
        if (artifactsGlob == null || artifactsGlob.isEmpty()) {
            artifactsGlob = "**";
        }

        VirtualFile artifactsRoot = build.getArtifactManager().root();
        String[] artifacts = null;
        try {
            artifacts = artifactsRoot.list(artifactsGlob);
        } catch (IOException e) {
            doFailAction(logger, "Can't list artifacts: " + e.getMessage());
            return;
        }

        if (artifacts.length == 0) {
            doFailAction(logger, "No artifacts are matched by given filter for upload");
            return;
        }

        String expandedCaption = Util.fixEmptyAndTrim(this.caption);
        if (expandedCaption != null) {
            try {
                EnvVars env = build.getEnvironment(listener);
                expandedCaption = env.expand(expandedCaption);
                int changeLogSizeLimit = SEND_DOCUMENT_CAPTION_SIZE_LIMIT - expandedCaption.length();
                String changeLog = getChangeLog(build, changeLogSizeLimit);
                env.addLine(ENV_VAR_CHANGELOG_NAME + "=" + changeLog);
                expandedCaption = env.expand(expandedCaption);
            } catch (Exception e) {
                doFailAction(logger, "Can't expand document caption '" + expandedCaption +
                        "': " + e.getMessage());
                return;
            }
        }

        TelegramUploaderDescriptor descriptor = getDescriptor();

        HttpHost httpProxy = null;
        try {
            httpProxy = getHttpProxy(descriptor.getHttpProxyUri());
        } catch (Exception e) {
            doFailAction(logger, "Can't set up HTTP proxy: " + e.getMessage());
            return;
        }

        String botToken = Secret.toString(descriptor.getBotToken());
        String httpProxyPassword = Secret.toString(descriptor.getHttpProxyPassword());

        try (CloseableHttpClient httpClient = getHttpClient(httpProxy,
                descriptor.getHttpProxyUser(), httpProxyPassword)) {
            for (String artifact : artifacts) {
                JSONObject telegramResponse = null;
                VirtualFile artifactVirtualFile = artifactsRoot.child(artifact);
                // Check for Telegram upload file size limit (50 MB for now)
                if (onlySendLink || artifactVirtualFile.length() > SEND_FILE_SIZE_LIMIT) {
                    // Choose action for file exceeded this limit
                    if (onlySendLink || sendLinkIfUploadSizeLimitExceeded) {
                        // Send link to the artifact instead of file itself
                        URL artifactUrl = new URL(build.getParent().getAbsoluteUrl()
                                + build.getNumber() + "/artifact/" + artifact);
                        logger.println("Uploading artifact link '" + artifactUrl
                                + "' to Telegram chat " + this.chatId);
                        try {
                            telegramResponse = sendTelegramLink(httpClient, httpProxy,
                                    botToken, expandedCaption, artifactUrl,
                                    artifactVirtualFile.length());
                            if (!isTelegramResponseOk(telegramResponse)) {
                                doFailAction(logger, "Error while uploading artifact link '"
                                        + artifactUrl + "' to Telegram chat " + this.chatId
                                        + getTelegramErrorMessage(telegramResponse));
                                continue;
                            }
                        } catch (AbortException ae) {
                            throw ae;
                        } catch (Exception e) {
                            doFailAction(logger, "Can't upload artifact link '" + artifactUrl
                                    + "' to Telegram chat " + this.chatId + ": " + e.getMessage());
                        }
                    } else {
                        // File limit exceeded, do fail action
                        doFailAction(logger, "Can't upload artifact '" + artifactVirtualFile
                                + "' to the Telegram: file is too big: "
                                + Functions.humanReadableByteSize(artifactVirtualFile.length())
                                + ", upload file size limit is: "
                                + Functions.humanReadableByteSize(SEND_FILE_SIZE_LIMIT));
                        continue;
                    }
                } else {
                    File artifactFile = new File(artifactVirtualFile.toURI());
                    logger.println("Uploading artifact '" + artifact + "' to the Telegram chat "
                            + this.chatId);
                    try {
                        telegramResponse = sendTelegramFile(httpClient, httpProxy,
                                botToken, expandedCaption, artifactFile);
                        if (!isTelegramResponseOk(telegramResponse)) {
                            doFailAction(logger, "Error while uploading artifact '" + artifact
                                    + "' to Telegram chat " + this.chatId
                                    + getTelegramErrorMessage(telegramResponse));
                            continue;
                        }
                    } catch (AbortException ae) {
                        throw ae;
                    } catch (Exception e) {
                        doFailAction(logger, "Can't upload artifact '" + artifactFile
                                + "' to Telegram chat " + this.chatId + ": " + e.getMessage());
                    }
                }
                if (this.forwardChatIds == null) {
                    continue;
                }
                if (telegramResponse == null) {
                    logger.println("Skip forwarding uploaded artifact because "
                            + "Telegram response is null");
                    continue;
                }
                JSONObject result = (JSONObject) telegramResponse.opt("result");
                if (result == null) {
                    logger.println("Skip forwarding uploaded artifact because "
                            + "no result was found in Telegram response");
                    continue;
                }
                int messageId = result.optInt("message_id", -1);
                if (messageId < 0) {
                    logger.println("Skip forwarding uploaded artifact because "
                            + "no message ID was found in Telegram response");
                    continue;
                }
                String[] forwardChatIds = this.forwardChatIds.split(",");
                for (String forwardChatId : forwardChatIds) {
                    forwardChatId = forwardChatId.trim();
                    if (forwardChatId.isEmpty()) {
                        continue;
                    }
                    logger.println("Forwarding artifact '" + artifact
                            + "' to Telegram chat " + forwardChatId);
                    try {
                        telegramResponse = forwardTelegramMessage(httpClient, httpProxy, botToken,
                                messageId, forwardChatId);
                        if (!isTelegramResponseOk(telegramResponse)) {
                            doFailAction(logger, "Error while forwarding artifact '" + artifact
                                    + "' to Telegram chat " + forwardChatId
                                    + getTelegramErrorMessage(telegramResponse));
                        }
                    } catch (AbortException ae) {
                        throw ae;
                    } catch (Exception e) {
                        doFailAction(logger, "Can't forward artifact '" + artifact
                                + "' to Telegram chat " + forwardChatId + ": " + e.getMessage());
                    }
                }
            }
        } catch (AbortException ae) {
            throw ae;
        } catch (IOException ioe) {
            // Ignore httpClient.close() IOException
        }
    }

    // Get all changesets from last successful build
    private List<ChangeLogSet<? extends Entry>> getChangeSets(@Nonnull Run<?, ?> run) {
        List<ChangeLogSet<? extends Entry>> result = new LinkedList<>();
        if (run instanceof AbstractBuild<?,?>) {
            AbstractBuild<?,?> b = (AbstractBuild<?,?>) run;
            Result r;
            do {
                ChangeLogSet<? extends Entry> changeLogSet = b.getChangeSet();
                result.add(0, changeLogSet);
                b = b.getPreviousBuild();
                r = (b != null) ? b.getResult() : null;
            } while (b != null && r != null && r.isWorseThan(Result.SUCCESS));
            return result;
        }
        return Collections.emptyList();
    }

    private static String escapeMarkdown(String str) {
        return str.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }

    private String getChangeLog(@Nonnull Run<?, ?> run, int sizeLimit) {
        List<String> changeLogLines = new ArrayList<>();
        for (ChangeLogSet<? extends Entry> changeSet : getChangeSets(run)) {
            for (Iterator<? extends ChangeLogSet.Entry> i = changeSet.iterator(); i.hasNext();) {
                ChangeLogSet.Entry change = i.next();
                String changeLogMessage = change.getMsg();
                int n = changeLogMessage.indexOf('\n');
                if (n > 0) {
                    changeLogMessage = changeLogMessage.substring(0, n).trim();
                }
                String changeLogLine = String.format("%n* %s: %s",
                        change.getAuthor().getDisplayName(), changeLogMessage);
                changeLogLines.add(escapeMarkdown(changeLogLine));
            }
        }
        Collections.reverse(changeLogLines);
        StringBuilder changeLog = new StringBuilder();
        for (String changeLogLine : changeLogLines) {
            if (changeLog.length() + changeLogLine.length() > sizeLimit) {
                break;
            }
            changeLog.insert(0, changeLogLine);
        }
        return changeLog.toString();
    }

    private void doFailAction(PrintStream logger, String message) throws AbortException {
        if (this.failBuildIfUploadFailed) {
            throw new AbortException(message);
        }
        logger.println(message);
    }

    private static HttpHost getHttpProxy(String httpProxyUri) {
        HttpHost httpProxy = null;
        if (httpProxyUri != null && !httpProxyUri.isEmpty()) {
            URI proxyUri = URI.create(httpProxyUri);
            httpProxy = new HttpHost(proxyUri.getHost(), proxyUri.getPort(), proxyUri.getScheme());
        }
        return httpProxy;
    }

    private static CloseableHttpClient getHttpClient(HttpHost proxyHost, String httpProxyUser,
            String httpProxyPassword) {
        CredentialsProvider httpProxyCredsProvider = new BasicCredentialsProvider();
        if (proxyHost != null && httpProxyUser != null && !httpProxyUser.trim().isEmpty()) {
            httpProxyCredsProvider.setCredentials(
                    new AuthScope(proxyHost.getHostName(), proxyHost.getPort()),
                    new UsernamePasswordCredentials(httpProxyUser.trim(), httpProxyPassword));
        }
        return HttpClients.custom().setDefaultCredentialsProvider(httpProxyCredsProvider).build();
    }

    private static ResponseHandler<JSONObject> getTelegramResponseHandler() {
        return new ResponseHandler<JSONObject>() {
            @Override
            public JSONObject handleResponse(HttpResponse response)
                    throws ClientProtocolException, IOException {
                JSONObject result = null;
                HttpEntity entity = response.getEntity();
                String entityString = (entity != null) ? EntityUtils.toString(entity) : null;
                try {
                    result = JSONObject.fromObject(entityString);
                } catch (JSONException e) {
                    // Do nothing
                }
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode < 200 || statusCode > 299) {
                    throw new ClientProtocolException("Unexpected response status: "
                            + statusCode + getTelegramErrorMessage(result));
                }
                return result;
            }
        };
    }

    static boolean isTelegramResponseOk(JSONObject response) {
        return response != null && response.optBoolean("ok");
    }

    static String getTelegramErrorDescription(JSONObject response) {
        return (response != null) ? response.optString("description", null) : null;
    }

    static String getTelegramErrorMessage(JSONObject response) {
        String errorDescription = getTelegramErrorDescription(response);
        return (errorDescription != null) ? " (" + errorDescription + ")" : "";
    }

    private static JSONObject sendTelegramRequest(HttpClient httpClient, HttpHost httpProxy,
            String botToken, String botMethod, HttpEntity botData) throws IOException {
        RequestConfig requestConfig = (httpProxy == null) ? RequestConfig.DEFAULT :
            RequestConfig.custom().setProxy(httpProxy).build();
        String requestUri = String.format("https://tgb-api.xyun2.workers.dev/bot%s/%s", botToken, botMethod);
        HttpUriRequest request = RequestBuilder.post(requestUri)
                .setEntity(botData)
                .setConfig(requestConfig)
                .build();
        return httpClient.execute(request, getTelegramResponseHandler());
    }

    public JSONObject sendTelegramLink(HttpClient httpClient, HttpHost httpProxy,
            String botToken, String linkCaption, URL link, long size)
                    throws IOException {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        Charset charset = Charset.forName("UTF-8");
        builder.setCharset(charset);

        String text = String.format("[%s](%s) (%s)", Paths.get(link.getPath()).getFileName(),
                link.toExternalForm(), Functions.humanReadableByteSize(size));
        if (linkCaption != null && !linkCaption.isEmpty()) {
            text += "\n\n" + linkCaption;
        }

        builder.addTextBody("chat_id", this.chatId, ContentType.DEFAULT_TEXT);
        builder.addTextBody("parse_mode", "Markdown", ContentType.DEFAULT_TEXT);
        builder.addTextBody("text", text, ContentType.TEXT_PLAIN.withCharset(charset));
        builder.addTextBody("disable_web_page_preview", "true", ContentType.DEFAULT_BINARY);

        if (this.silent) {
            builder.addTextBody("disable_notification", "true", ContentType.DEFAULT_TEXT);
        }

        HttpEntity data = builder.build();

        return sendTelegramRequest(httpClient, httpProxy, botToken, "sendMessage", data);
    }

    public JSONObject sendTelegramFile(HttpClient httpClient, HttpHost httpProxy,
            String botToken, String fileCaption, File file) throws IOException {
        // Build multipart upload request
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        Charset charset = Charset.forName("UTF-8");
        builder.setCharset(charset);

        // Add parts to multipart request
        builder.addTextBody("chat_id", this.chatId, ContentType.DEFAULT_TEXT);

        if (this.silent) {
            builder.addTextBody("disable_notification", "true", ContentType.DEFAULT_TEXT);
        }

        if (fileCaption != null && !fileCaption.isEmpty()) {
            builder.addTextBody("parse_mode", "Markdown", ContentType.DEFAULT_TEXT);
            builder.addTextBody("caption", fileCaption, ContentType.TEXT_PLAIN.withCharset(charset));
        }

        builder.addBinaryBody("document", file, ContentType.DEFAULT_BINARY, file.getName());

        HttpEntity data = builder.build();

        return sendTelegramRequest(httpClient, httpProxy, botToken, "sendDocument", data);
    }

    public static void checkTelegramConnection(String botToken, String httpProxyUri,
            String httpProxyUser, String httpProxyPassword)
                    throws ClientProtocolException, IOException {
        HttpHost httpProxy = getHttpProxy(httpProxyUri);
        try (CloseableHttpClient httpClient = getHttpClient(httpProxy,
                httpProxyUser, httpProxyPassword)) {
            JSONObject response = sendTelegramRequest(httpClient, httpProxy,
                    botToken, "getUpdates", null);
            if (!isTelegramResponseOk(response)) {
                throw new ClientProtocolException(getTelegramErrorDescription(response));
            }
        }
    }

    public JSONObject forwardTelegramMessage(HttpClient httpClient, HttpHost httpProxy,
            String botToken, int messageId, String forwardChatId) throws IOException {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        Charset charset = Charset.forName("UTF-8");
        builder.setCharset(charset);

        builder.addTextBody("from_chat_id", this.chatId, ContentType.DEFAULT_TEXT);
        builder.addTextBody("chat_id", forwardChatId, ContentType.DEFAULT_TEXT);
        builder.addTextBody("message_id", Integer.toString(messageId), ContentType.DEFAULT_TEXT);

        if (this.silent) {
            builder.addTextBody("disable_notification", "true", ContentType.DEFAULT_TEXT);
        }

        HttpEntity data = builder.build();

        return sendTelegramRequest(httpClient, httpProxy, botToken, "forwardMessage", data);
    }

    @Symbol("telegramUploader")
    @Extension
    public static final class TelegramUploaderDescriptor extends BuildStepDescriptor<Publisher> {
        private Secret botToken;
        private String httpProxyUri;
        private String httpProxyUser;
        private Secret httpProxyPassword;

        public TelegramUploaderDescriptor() {
            load();
        }

        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Upload artifacts to Telegram";
        }

        public FormValidation doCheckChatId(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.error("Cannot be empty");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckBotToken(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.error("Bot token must not be empty");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckHttpProxyUri(@QueryParameter String value) {
            if (!value.isEmpty()) {
                try {
                    URI.create(value);
                } catch (Exception e) {
                    return FormValidation.error("Invalid HTTP proxy URI: %s", e.getMessage());
                }
            }

            return FormValidation.ok();
        }

        public FormValidation doTestConnection(
                @QueryParameter("botToken") Secret token,
                @QueryParameter("httpProxyUri") String proxyUri,
                @QueryParameter("httpProxyUser") String proxyUser,
                @QueryParameter("httpProxyPassword") Secret proxyPassword) throws IOException {
            final Jenkins jenkins = Jenkins.get();
            if (jenkins == null) {
                throw new IOException("Jenkins instance is not ready");
            }
            jenkins.checkPermission(Jenkins.ADMINISTER);

            try {
                TelegramUploader.checkTelegramConnection(Secret.toString(token),
                        proxyUri, proxyUser, Secret.toString(proxyPassword));
            } catch (Exception e) {
                return FormValidation.errorWithMarkup("<p>Can't connect to Telegram!</p><pre>" +
                        Util.escape(Functions.printThrowable(e)) + "</pre>");
            }

            return FormValidation.ok("Successfully connected to Telegram!");
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            JSONObject config = json.getJSONObject("telegram-uploader");
            this.botToken = Secret.fromString(config.getString("botToken"));
            this.httpProxyUri = config.getString("httpProxyUri");
            this.httpProxyUser = config.getString("httpProxyUser");
            this.httpProxyPassword = Secret.fromString(config.getString("httpProxyPassword"));
            save();
            return true;
        }

        public Secret getBotToken() {
            return botToken;
        }

        public String getHttpProxyUri() {
            return httpProxyUri;
        }

        public String getHttpProxyUser() {
            return httpProxyUser;
        }

        public Secret getHttpProxyPassword() {
            return httpProxyPassword;
        }
    }
}
