package com.networknt.security;

import com.networknt.config.Config;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.keys.resolvers.X509VerificationKeyResolver;
import org.owasp.encoder.Encode;
import org.slf4j.ext.XLogger;
import org.slf4j.ext.XLoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by steve on 01/09/16.
 */
public class JwtHelper {
    static final XLogger logger = XLoggerFactory.getXLogger(JwtHelper.class);
    static final String JWT_CONFIG = "jwt";
    static final String SECURITY_CONFIG = "security";
    static final String JWT_CERTIFICATE = "certificate";
    static final String JwT_CLOCK_SKEW_IN_SECONDS = "clockSkewInSeconds";
    static List<X509Certificate> certificates;
    static Map<String, Object> securityConfig = (Map)Config.getInstance().getJsonMapConfig(SECURITY_CONFIG);
    static Map<String, Object> jwtConfig = (Map)securityConfig.get(JWT_CONFIG);

    public static String getJwt(JwtClaims claims) throws Exception {
        String jwt = null;
        RSAPrivateKey privateKey = (RSAPrivateKey) getPrivateKey("/config/oauth/primary.jks", "password", "selfsigned");
        // A JWT is a JWS and/or a JWE with JSON claims as the payload.
        // In this example it is a JWS nested inside a JWE
        // So we first create a JsonWebSignature object.
        JsonWebSignature jws = new JsonWebSignature();

        // The payload of the JWS is JSON content of the JWT Claims
        jws.setPayload(claims.toJson());

        // The JWT is signed using the sender's private key
        jws.setKey(privateKey);

        // Set the signature algorithm on the JWT/JWS that will integrity protect the claims
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        // Sign the JWS and produce the compact serialization, which will be the inner JWT/JWS
        // representation, which is a string consisting of three dot ('.') separated
        // base64url-encoded parts in the form Header.Payload.Signature
        jwt = jws.getCompactSerialization();
        return jwt;
    }

    public static JwtClaims getDefaultJwtClaims() {
        JwtConfig config = (JwtConfig) Config.getInstance().getJsonObjectConfig(JWT_CONFIG, JwtConfig.class);

        JwtClaims claims = new JwtClaims();

        claims.setIssuer(config.getIssuer());
        claims.setAudience(config.getAudience());
        claims.setExpirationTimeMinutesInTheFuture(config.getExpiredInMinutes());
        claims.setGeneratedJwtId(); // a unique identifier for the token
        claims.setIssuedAtToNow();  // when the token was issued/created (now)
        claims.setNotBeforeMinutesInThePast(2); // time before which the token is not yet valid (2 minutes ago)
        claims.setClaim("version", config.getVersion());
        return claims;

    }

    private static PrivateKey getPrivateKey(String filename, String password, String key) {
        PrivateKey privateKey = null;

        try {
            KeyStore keystore = KeyStore.getInstance("JKS");
            keystore.load(JwtHelper.class.getResourceAsStream(filename),
                    password.toCharArray());

            privateKey = (PrivateKey) keystore.getKey(key,
                    password.toCharArray());
        } catch (Exception e) {
            logger.error("Exception:", e);
        }

        if (privateKey == null) {
            logger.error("Failed to retrieve private key from keystore");
        }

        return privateKey;
    }


    static public X509Certificate readCertificate(String filename)
            throws Exception {
        InputStream inStream = null;
        X509Certificate cert = null;
        try {
            inStream = Config.getInstance().getInputStreamFromFile(filename);
            if (inStream != null) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                cert = (X509Certificate) cf.generateCertificate(inStream);
            } else {
                logger.info("Certificate " + Encode.forJava(filename) + " not found.");
            }
        } catch (Exception e) {
            logger.error("Exception: ", e);
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException ioe) {
                    logger.error("Exception: ", ioe);
                }
            }
        }
        return cert;
    }

    static {
        certificates = new ArrayList<X509Certificate>();
        List<String> files = (List<String>)jwtConfig.get(JWT_CERTIFICATE);
        try {
            for (String file : files) {
                certificates.add(readCertificate(file));
            }
        } catch (Exception e) {
            logger.error("Exception:", e);
        }
    }

    public static JwtClaims verifyJwt(String jwt) throws InvalidJwtException {
        JwtClaims claims = null;
        for(X509Certificate certificate: certificates) {
            X509VerificationKeyResolver x509VerificationKeyResolver = new X509VerificationKeyResolver(
                    certificate);
            x509VerificationKeyResolver.setTryAllOnNoThumbHeader(true);

            JwtConsumer jwtConsumer = new JwtConsumerBuilder()
                    .setRequireExpirationTime()
                    .setAllowedClockSkewInSeconds(
                            (Integer) jwtConfig.get(JwT_CLOCK_SKEW_IN_SECONDS))
                    .setSkipDefaultAudienceValidation()
                    .setVerificationKeyResolver(x509VerificationKeyResolver)
                    .build();

            // Validate the JWT and process it to the Claims
            JwtContext jwtContext = jwtConsumer.process(jwt);
            claims = jwtContext.getJwtClaims();
        }
        return claims;
    }
}
