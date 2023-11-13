/*
 * This file is part of MinecraftAuth - https://github.com/RaphiMC/MinecraftAuth
 * Copyright (C) 2023 RK_01/RaphiMC and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.minecraftauth.step.xbl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.EqualsAndHashCode;
import lombok.Value;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.responsehandler.XblResponseHandler;
import net.raphimc.minecraftauth.step.AbstractStep;
import net.raphimc.minecraftauth.step.xbl.session.StepInitialXblSession;
import net.raphimc.minecraftauth.util.CryptUtil;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;

import java.time.Instant;
import java.time.ZoneId;

public class StepXblTitleToken extends AbstractStep<StepInitialXblSession.InitialXblSession, StepXblTitleToken.XblTitleToken> {

    public static final String XBL_TITLE_URL = "https://title.auth.xboxlive.com/title/authenticate";

    public StepXblTitleToken(final AbstractStep<?, StepInitialXblSession.InitialXblSession> prevStep) {
        super("titleToken", prevStep);
    }

    @Override
    public XblTitleToken applyStep(final HttpClient httpClient, final StepInitialXblSession.InitialXblSession initialXblSession) throws Exception {
        MinecraftAuth.LOGGER.info("Authenticating title with Xbox Live...");

        if (initialXblSession.getXblDeviceToken() == null) throw new IllegalStateException("A XBL Device Token is needed for Title authentication");

        final JsonObject postData = new JsonObject();
        final JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "t=" + initialXblSession.getMsaToken().getAccessToken());
        properties.addProperty("DeviceToken", initialXblSession.getXblDeviceToken().getToken());
        properties.add("ProofKey", CryptUtil.getProofKey(initialXblSession.getXblDeviceToken().getPublicKey()));
        postData.add("Properties", properties);
        postData.addProperty("RelyingParty", "http://auth.xboxlive.com");
        postData.addProperty("TokenType", "JWT");

        final HttpPost httpPost = new HttpPost(XBL_TITLE_URL);
        httpPost.setEntity(new StringEntity(postData.toString(), ContentType.APPLICATION_JSON));
        httpPost.addHeader("x-xbl-contract-version", "1");
        httpPost.addHeader(CryptUtil.getSignatureHeader(httpPost, initialXblSession.getXblDeviceToken().getPrivateKey()));
        final String response = httpClient.execute(httpPost, new XblResponseHandler());
        final JsonObject obj = JsonParser.parseString(response).getAsJsonObject();

        final XblTitleToken xblTitleToken = new XblTitleToken(
                Instant.parse(obj.get("NotAfter").getAsString()).toEpochMilli(),
                obj.get("Token").getAsString(),
                obj.getAsJsonObject("DisplayClaims").getAsJsonObject("xti").get("tid").getAsString(),
                initialXblSession
        );
        MinecraftAuth.LOGGER.info("Got XBL Title Token, expires: " + Instant.ofEpochMilli(xblTitleToken.getExpireTimeMs()).atZone(ZoneId.systemDefault()));
        return xblTitleToken;
    }

    @Override
    public XblTitleToken refresh(final HttpClient httpClient, final XblTitleToken xblTitleToken) throws Exception {
        if (xblTitleToken.isExpired()) return super.refresh(httpClient, xblTitleToken);

        return xblTitleToken;
    }

    @Override
    public XblTitleToken fromJson(final JsonObject json) {
        final StepInitialXblSession.InitialXblSession initialXblSession = this.prevStep != null ? this.prevStep.fromJson(json.getAsJsonObject(this.prevStep.name)) : null;
        return new XblTitleToken(
                json.get("expireTimeMs").getAsLong(),
                json.get("token").getAsString(),
                json.get("titleId").getAsString(),
                initialXblSession
        );
    }

    @Override
    public JsonObject toJson(final XblTitleToken xblTitleToken) {
        final JsonObject json = new JsonObject();
        json.addProperty("expireTimeMs", xblTitleToken.expireTimeMs);
        json.addProperty("token", xblTitleToken.token);
        json.addProperty("titleId", xblTitleToken.titleId);
        if (this.prevStep != null) json.add(this.prevStep.name, this.prevStep.toJson(xblTitleToken.initialXblSession));
        return json;
    }

    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class XblTitleToken extends AbstractStep.StepResult<StepInitialXblSession.InitialXblSession> {

        long expireTimeMs;
        String token;
        String titleId;
        StepInitialXblSession.InitialXblSession initialXblSession;

        @Override
        protected StepInitialXblSession.InitialXblSession prevResult() {
            return this.initialXblSession;
        }

        @Override
        public boolean isExpired() {
            return this.expireTimeMs <= System.currentTimeMillis();
        }

    }

}
