package org.apache.dolphinscheduler.api.sso;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import javax.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Component
@RequiredArgsConstructor
public class JwtVerifier {

    @Value("${sso.jwt.alg:HS256}")
    private String alg;
    @Value("${sso.jwt.publicKeyPem}")
    private String publicKeyPem;
    @Value("${sso.jwt.secret:abcdefghijklmnopqrstuvwxyz}")
    private String secret;
    @Value("${sso.issuer:qdata}")
    private String expectedIss;
    @Value("${sso.audience:dolphinscheduler}")
    private String expectedAud;
    @Value("${sso.clockSkewSeconds:60}")
    private long skew;

    private JWSVerifier rsVerifier;
    private JWSVerifier hsVerifier;

    @PostConstruct
    public void init() throws Exception {
        if ("RS256".equalsIgnoreCase(alg)) {
            PublicKey pk = readRsaPublicKey(publicKeyPem);
            rsVerifier = new RSASSAVerifier((RSAPublicKey) pk);
        } else if ("HS256".equalsIgnoreCase(alg)) {
            hsVerifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IllegalStateException("Unsupported alg " + alg);
        }
    }

    public JWTClaimsSet verify(String jwt) {
        try {
            SignedJWT sjwt = SignedJWT.parse(jwt);
            boolean ok = "RS256".equalsIgnoreCase(alg) ? sjwt.verify(rsVerifier) : sjwt.verify(hsVerifier);
            if (!ok)
                throw new BadCredentialsException("JWT signature invalid");

            JWTClaimsSet c = sjwt.getJWTClaimsSet();
            if (!expectedIss.equals(c.getIssuer()))
                throw new BadCredentialsException("iss mismatch");
            List<String> aud = c.getAudience();
            if (aud == null || !aud.contains(expectedAud))
                throw new BadCredentialsException("aud mismatch");

            Instant now = Instant.now();
            Date exp = c.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(now.minusSeconds(skew)))
                throw new BadCredentialsException("exp invalid");
            Date nbf = c.getNotBeforeTime();
            if (nbf != null && nbf.toInstant().isAfter(now.plusSeconds(skew)))
                throw new BadCredentialsException("nbf invalid");

            return c;
        } catch (ParseException | JOSEException e) {
            throw new BadCredentialsException("JWT parse/verify error", e);
        }
    }

    private PublicKey readRsaPublicKey(String pem) throws Exception {
        String p = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(p);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }
}
