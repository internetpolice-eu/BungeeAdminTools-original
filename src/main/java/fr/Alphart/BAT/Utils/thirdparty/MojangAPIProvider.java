package fr.Alphart.BAT.Utils.thirdparty;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;

import com.google.gson.Gson;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import fr.Alphart.BAT.BAT;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;
import org.jetbrains.annotations.Nullable;

public class MojangAPIProvider {
    private static final Gson gson = new Gson();
    private static final String UUID_RETRIEVAL_URL = "https://api.mojang.com/users/profiles/minecraft/";

    public static @Nullable String getUUID(final String playerName) {
        try {
            String json = requestFromMojang(UUID_RETRIEVAL_URL + playerName);
            if (json != null) {
                JsonObject profile = gson.fromJson(json, JsonObject.class);
                if (profile != null && profile.get("id") != null) {
                    return profile.get("id").getAsString();
                }
            }
        } catch (JsonSyntaxException ex) {
            BAT.getInstance().getLogger().log(Level.WARNING, "Failed to parse Mojang JSON.", ex);
        }
        return null;
    }

    private static @Nullable String requestFromMojang(String url) {
        try (final CloseableHttpClient httpClient = HttpClients.createDefault()) {
            URI uri = new URIBuilder(url).build();

            int CONNECTION_TIMEOUT = 10;
            RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(CONNECTION_TIMEOUT))
                .setConnectTimeout(Timeout.ofSeconds(CONNECTION_TIMEOUT))
                .setResponseTimeout(Timeout.ofSeconds(CONNECTION_TIMEOUT))
                .build();

            HttpGet request = new HttpGet(uri);
            request.setConfig(requestConfig);
            ClassicHttpResponse response = httpClient.execute(request);

            int status = response.getCode();
            if (status >= HttpStatus.SC_SUCCESS && status < HttpStatus.SC_REDIRECTION) {
                final HttpEntity entity = response.getEntity();
                try {
                    return entity != null ? EntityUtils.toString(entity) : null;
                } catch (final ParseException ex) {
                    BAT.getInstance().getLogger().log(Level.SEVERE, "Failed to parse HTTP response.", ex);
                    return null;
                }
            }
        } catch (IOException ex) {
            BAT.getInstance().getLogger().log(Level.SEVERE, "Exception while requesting " + url + " from Mojang.", ex);
        } catch (URISyntaxException ex) {
            BAT.getInstance().getLogger().log(Level.WARNING, "Trying to request invalid URL: " + url, ex);
        }

        return null;
    }
}
