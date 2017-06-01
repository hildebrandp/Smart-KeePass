package com.keepassdroid.mycode;

/**
 * Created by Pascal Hildebrand on 17.05.2017.
 */

import android.util.Log;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.*;
import javax.crypto.spec.*;


public class diffieHellman {

    private String log = "DH-Class";
    private byte[] intPubKey;
    private int intSharedLength;
    private byte[] extPubKey;

    private byte[] intSharedSecret;

    private KeyAgreement intKeyAgree;
    private SecretKey intAESKey;

    private Cipher intCipher;

    public void generateKeyPair() throws Exception
    {
        DHParameterSpec dhSkipParamSpec;
        Log.v(log, "Creating Diffie Hellman Parameters...");

        AlgorithmParameterGenerator parameterGenerator = AlgorithmParameterGenerator.getInstance("DH");
        parameterGenerator.init(256);
        AlgorithmParameters parameters = parameterGenerator.generateParameters();
        dhSkipParamSpec = (DHParameterSpec)parameters.getParameterSpec(DHParameterSpec.class);

        Log.v(log, "Creating own Keypair...");
        KeyPairGenerator KpairGen = KeyPairGenerator.getInstance("DH");
        KpairGen.initialize(dhSkipParamSpec);
        KeyPair Kpair = KpairGen.generateKeyPair();

        Log.v(log, "Initialization...");
        intKeyAgree = KeyAgreement.getInstance("DH");
        intKeyAgree.init(Kpair.getPrivate());

        intPubKey = Kpair.getPublic().getEncoded();
    }

    public void generateKeyFactory(byte[] extPubKey) throws Exception
    {
        this.extPubKey = extPubKey;

        Log.v(log, "Create Key with external PubKey...");
        KeyFactory intKeyFac = KeyFactory.getInstance("DH");
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(extPubKey);
        PublicKey extPubKeyFac = intKeyFac.generatePublic(x509KeySpec);

        Log.v(log, "Internal Execute PHASE1 ...");
        intKeyAgree.doPhase(extPubKeyFac, true);
    }

    public byte[] getPublicKey()
    {
        return intPubKey;
    }

    public void setExtPubKey(byte[] key)
    {
        this.extPubKey = key;
    }

    public int getSharedLength()
    {
        Log.v(log, "Generate Shared Secret...");
        intSharedSecret = intKeyAgree.generateSecret();
        intSharedLength = intSharedSecret.length;
        return intSharedLength;
    }

    public void genSecretKey() throws Exception
    {
        Log.v(log, "Generate AES Key...");
        intAESKey = intKeyAgree.generateSecret("AES");
        intCipher = Cipher.getInstance("AES");
    }

    public byte[] encyptText(byte[] textToEncrypt) throws Exception
    {
        intCipher.init(Cipher.ENCRYPT_MODE, intAESKey);
        return intCipher.doFinal(textToEncrypt);
    }

    public byte[] decryptText(byte[] textToDecrypt) throws Exception
    {
        intCipher.init(Cipher.DECRYPT_MODE, intAESKey);
        return intCipher.doFinal(textToDecrypt);
    }

}
