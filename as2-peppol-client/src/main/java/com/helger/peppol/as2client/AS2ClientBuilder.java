/**
 * Copyright (C) 2014-2021 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.peppol.as2client;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import javax.activation.DataHandler;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unece.cefact.namespaces.sbdh.StandardBusinessDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.helger.as2lib.cert.IStorableCertificateFactory;
import com.helger.as2lib.client.AS2Client;
import com.helger.as2lib.client.AS2ClientRequest;
import com.helger.as2lib.client.AS2ClientResponse;
import com.helger.as2lib.client.AS2ClientSettings;
import com.helger.as2lib.crypto.ECryptoAlgorithmSign;
import com.helger.as2lib.disposition.DispositionOptions;
import com.helger.as2lib.util.dump.IHTTPIncomingDumper;
import com.helger.as2lib.util.dump.IHTTPOutgoingDumper;
import com.helger.as2lib.util.dump.IHTTPOutgoingDumperFactory;
import com.helger.commons.ValueEnforcer;
import com.helger.commons.annotation.Nonempty;
import com.helger.commons.annotation.OverrideOnDemand;
import com.helger.commons.annotation.ReturnsMutableCopy;
import com.helger.commons.collection.impl.CommonsArrayList;
import com.helger.commons.collection.impl.ICommonsList;
import com.helger.commons.datetime.PDTFactory;
import com.helger.commons.email.EmailAddressHelper;
import com.helger.commons.functional.IConsumer;
import com.helger.commons.functional.ISupplier;
import com.helger.commons.http.CHttpHeader;
import com.helger.commons.io.resource.FileSystemResource;
import com.helger.commons.io.resource.IReadableResource;
import com.helger.commons.io.resource.inmemory.ReadableResourceByteArray;
import com.helger.commons.io.stream.NonBlockingByteArrayOutputStream;
import com.helger.commons.mime.CMimeType;
import com.helger.commons.mime.IMimeType;
import com.helger.commons.state.ETriState;
import com.helger.commons.string.StringHelper;
import com.helger.commons.url.URLHelper;
import com.helger.mail.cte.EContentTransferEncoding;
import com.helger.peppol.sbdh.CPeppolSBDH;
import com.helger.peppol.sbdh.PeppolSBDHDocument;
import com.helger.peppol.sbdh.write.PeppolSBDHDocumentWriter;
import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.peppol.smp.ISMPTransportProfile;
import com.helger.peppol.utils.EPeppolCertificateCheckResult;
import com.helger.peppol.utils.PeppolCertificateChecker;
import com.helger.peppol.utils.PeppolCertificateHelper;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.factory.PeppolIdentifierFactory;
import com.helger.peppolid.peppol.PeppolIdentifierHelper;
import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.VESID;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.engine.source.IValidationSourceXML;
import com.helger.phive.engine.source.ValidationSourceXML;
import com.helger.phive.peppol.PeppolValidation;
import com.helger.sbdh.CSBDH;
import com.helger.sbdh.SBDMarshaller;
import com.helger.security.keystore.IKeyStoreType;
import com.helger.smpclient.exception.SMPClientException;
import com.helger.smpclient.peppol.ISMPServiceMetadataProvider;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.xml.namespace.INamespaceContext;
import com.helger.xml.namespace.MapBasedNamespaceContext;
import com.helger.xml.serialize.read.DOMReader;
import com.helger.xsds.peppol.smp1.EndpointType;
import com.helger.xsds.peppol.smp1.SignedServiceMetadataType;

/**
 * A builder class for easy usage of the AS2 client for sending messages to a
 * Peppol participant. After building use the {@link #sendSynchronous()} message
 * to trigger the sending. All parameters that not explicitly have a default
 * value must be set otherwise the verification process will fail.
 *
 * @author Philip Helger
 */
@NotThreadSafe
public class AS2ClientBuilder
{
  /** Default AS2 subject */
  public static final String DEFAULT_AS2_SUBJECT = "Peppol AS2 message";
  /** Default AS2 signing algorithm for PEPPOL AS2 profile v1 */
  public static final ECryptoAlgorithmSign DEFAULT_SIGNING_ALGORITHM = ECryptoAlgorithmSign.DIGEST_SHA_1;
  /** Default AS2 signing algorithm for PEPPOL AS2 profile v2 */
  public static final ECryptoAlgorithmSign DEFAULT_SIGNING_ALGORITHM_V2 = ECryptoAlgorithmSign.DIGEST_SHA_256;
  /** Default AS2 message ID format */
  public static final String DEFAULT_AS2_MESSAGE_ID_FORMAT = "OpenPEPPOL-$date.ddMMyyyyHHmmssZ$-$rand.1234$@$msg.sender.as2_id$_$msg.receiver.as2_id$";
  /** "P" + country code (e.g. "DK" for Denmark or "OP" for OpenPEPPOL */
  public static final String APP_PREFIX_V3 = "P";
  /** By default a data handler should be used */
  public static final boolean DEFAULT_USE_DATA_HANDLER = true;
  /** The default mime type to be used for outgoing messages */
  public static final IMimeType DEFAULT_MIME_TYPE = CMimeType.APPLICATION_XML;
  /** The default validation handler doing nothing */
  public static final IAS2ClientBuilderValidatonResultHandler DEFAULT_VALIDATION_RESULT_HANDLER = new IAS2ClientBuilderValidatonResultHandler ()
  {};
  public final Consumer <ISMPTransportProfile> DEFAULT_SELECTED_TRANSPORT_PROFILE_CONSUMER = aTP -> {
    // Overwrite the signing algorithm depending on the found transport profile
    if (aTP == ESMPTransportProfile.TRANSPORT_PROFILE_AS2)
      setAS2SigningAlgorithm (ECryptoAlgorithmSign.DIGEST_SHA_1);
    else
      if (aTP == ESMPTransportProfile.TRANSPORT_PROFILE_AS2_V2)
        setAS2SigningAlgorithm (ECryptoAlgorithmSign.DIGEST_SHA_256);
  };

  /**
   * Default {@link AS2Client} supplier to be used. You can derive from this
   * class to add e.g. a proxy to the AS2Client or configure it in other ways.
   *
   * @author Philip Helger
   * @since 3.1.0
   */
  public static class AS2ClientSupplier implements ISupplier <AS2Client>
  {
    @Nonnull
    @OverridingMethodsMustInvokeSuper
    public AS2Client get ()
    {
      final AS2Client ret = new AS2Client ();
      // Use this special sender module factory
      ret.setAS2SenderModuleFactory (PeppolAS2SenderModule::new);
      return ret;
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger (AS2ClientBuilder.class);

  /**
   * The default implementation of
   * {@link IAS2ClientBuilderCertificateCheckResultHandler} that can be used as
   * a basis in derived classes.
   *
   * @author Philip Helger
   * @since 3.1.0
   */
  protected class CertificateCheckResultHandler implements IAS2ClientBuilderCertificateCheckResultHandler
  {
    public void onCertificateCheckResult (@Nonnull final X509Certificate aAPCertificate,
                                          @Nonnull final LocalDateTime aCheckDT,
                                          @Nonnull final EPeppolCertificateCheckResult eCertCheckResult) throws AS2ClientBuilderException
    {
      if (eCertCheckResult.isInvalid ())
      {
        // By default an invalid certificate leads to a rejection
        getMessageHandler ().error ("The received AP certificate is not valid (at " +
                                    aCheckDT +
                                    ") and cannot be used for sending. Aborting. Reason: " +
                                    eCertCheckResult.getReason ());
      }
    }
  }

  private IAS2ClientBuilderMessageHandler m_aMessageHandler = new DefaultAS2ClientBuilderMessageHandler ();
  private IKeyStoreType m_aKeyStoreType;
  private File m_aKeyStoreFile;
  private byte [] m_aKeyStoreBytes;
  private String m_sKeyStorePassword;
  private boolean m_bSaveKeyStoreChangesToFile = IStorableCertificateFactory.DEFAULT_SAVE_CHANGES_TO_FILE;
  private String m_sAS2Subject = DEFAULT_AS2_SUBJECT;
  private String m_sSenderAS2ID;
  private String m_sSenderAS2Email;
  private String m_sSenderAS2KeyAlias;
  private String m_sReceiverAS2ID;
  private String m_sReceiverAS2KeyAlias;
  private String m_sReceiverAS2Url;
  private X509Certificate m_aReceiverCert;
  private IAS2ClientBuilderCertificateCheckResultHandler m_aReceiverCertCheckResultHandler = new CertificateCheckResultHandler ();
  private ECryptoAlgorithmSign m_eSigningAlgo = DEFAULT_SIGNING_ALGORITHM;
  private String m_sMessageIDFormat = DEFAULT_AS2_MESSAGE_ID_FORMAT;
  private int m_nConnectTimeoutMS = AS2ClientSettings.DEFAULT_CONNECT_TIMEOUT_MS;
  private int m_nReadTimeoutMS = AS2ClientSettings.DEFAULT_READ_TIMEOUT_MS;

  private IReadableResource m_aBusinessDocumentRes;
  private Element m_aBusinessDocumentElement;
  private IParticipantIdentifier m_aPeppolSenderID;
  private IParticipantIdentifier m_aPeppolReceiverID;
  private IDocumentTypeIdentifier m_aPeppolDocumentTypeID;
  private IProcessIdentifier m_aPeppolProcessID;
  private VESID m_aVESID;
  private ISMPServiceMetadataProvider m_aSMPClient;
  private ISupplier <AS2Client> m_aAS2ClientFactory = new AS2ClientSupplier ();
  private INamespaceContext m_aSBDHNamespaceContext;
  private IConsumer <byte []> m_aSBDHBytesConsumer;
  private EContentTransferEncoding m_eCTE = EContentTransferEncoding.AS2_DEFAULT;
  private IAS2ClientBuilderValidatonResultHandler m_aValidationResultHandler = DEFAULT_VALIDATION_RESULT_HANDLER;
  private transient ValidationExecutorSetRegistry <IValidationSourceXML> m_aVESRegistry;
  private IHTTPOutgoingDumperFactory m_aHttpOutgoingDumperFactory;
  private IHTTPIncomingDumper m_aHttpIncomingDumper;
  private boolean m_bUseDataHandler = DEFAULT_USE_DATA_HANDLER;
  private IMimeType m_aMimeType = DEFAULT_MIME_TYPE;
  private final ICommonsList <ISMPTransportProfile> m_aTransportProfiles = new CommonsArrayList <> (ESMPTransportProfile.TRANSPORT_PROFILE_AS2_V2,
                                                                                                    ESMPTransportProfile.TRANSPORT_PROFILE_AS2);
  private Consumer <ISMPTransportProfile> m_aSelectedTransportProfileConsumer = DEFAULT_SELECTED_TRANSPORT_PROFILE_CONSUMER;

  /**
   * Default constructor.
   */
  public AS2ClientBuilder ()
  {}

  /**
   * @return The internal message handler. Only required for derived classes
   *         that want to add additional verification mechanisms.
   */
  @Nonnull
  protected final IAS2ClientBuilderMessageHandler getMessageHandler ()
  {
    return m_aMessageHandler;
  }

  /**
   * Set the message handler to be used by the {@link #verifyContent()} method.
   * By default an instance of {@link DefaultAS2ClientBuilderMessageHandler} is
   * used so this method should only be called if you have special auditing
   * requirements.
   *
   * @param aMessageHandler
   *        The message handler to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setMessageHandler (@Nonnull final IAS2ClientBuilderMessageHandler aMessageHandler)
  {
    m_aMessageHandler = ValueEnforcer.notNull (aMessageHandler, "MessageHandler");
    return this;
  }

  /**
   * Set the key store type, file and password for the AS2 client. The key store
   * must be an existing containing at least the key alias of the sender (see
   * {@link #setSenderAS2ID(String)}). The key store file must be writable as
   * dynamically certificates of partners are added.
   *
   * @param aKeyStoreType
   *        The key store type. May not be <code>null</code>.
   * @param aKeyStoreFile
   *        The existing key store file. Must exist and may not be
   *        <code>null</code>.
   * @param sKeyStorePassword
   *        The password to the key store. May not be <code>null</code> but
   *        empty.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setKeyStore (@Nullable final IKeyStoreType aKeyStoreType,
                                       @Nullable final File aKeyStoreFile,
                                       @Nullable final String sKeyStorePassword)
  {
    m_aKeyStoreType = aKeyStoreType;
    m_aKeyStoreFile = aKeyStoreFile;
    m_aKeyStoreBytes = null;
    m_sKeyStorePassword = sKeyStorePassword;
    return this;
  }

  /**
   * Set the key store type, content and password for the AS2 client. The key
   * store must be an existing containing at least the key alias of the sender
   * (see {@link #setSenderAS2ID(String)}). Changes to the keystore will NOT be
   * saved.
   *
   * @param aKeyStoreType
   *        The key store type. May not be <code>null</code>.
   * @param aKeyStoreBytes
   *        The key store bytes. May not be <code>null</code>.
   * @param sKeyStorePassword
   *        The password to the key store. May not be <code>null</code> but
   *        empty.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setKeyStore (@Nullable final IKeyStoreType aKeyStoreType,
                                       @Nullable final byte [] aKeyStoreBytes,
                                       @Nullable final String sKeyStorePassword)
  {
    m_aKeyStoreType = aKeyStoreType;
    m_aKeyStoreFile = null;
    m_aKeyStoreBytes = aKeyStoreBytes;
    m_sKeyStorePassword = sKeyStorePassword;
    return this;
  }

  /**
   * Change the behavior if all changes to the key store should trigger a saving
   * to the original file.
   *
   * @param bSaveKeyStoreChangesToFile
   *        <code>true</code> if key store changes should be written back to the
   *        file, <code>false</code> if not.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSaveKeyStoreChangesToFile (final boolean bSaveKeyStoreChangesToFile)
  {
    m_bSaveKeyStoreChangesToFile = bSaveKeyStoreChangesToFile;
    return this;
  }

  /**
   * Set the subject for the AS2 message. By default
   * {@value #DEFAULT_AS2_SUBJECT} is used so you don't need to set it.
   *
   * @param sAS2Subject
   *        The new AS2 subject. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setAS2Subject (@Nullable final String sAS2Subject)
  {
    m_sAS2Subject = sAS2Subject;
    return this;
  }

  /**
   * Set the AS2 sender ID (your ID). It is mapped to the "AS2-From" header. For
   * Peppol the AS2 sender ID must be the common name (CN) of the sender's AP
   * certificate subject. Therefore it usually starts with
   * {@link #APP_PREFIX_V3}
   *
   * @param sSenderAS2ID
   *        The AS2 sender ID to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSenderAS2ID (@Nullable final String sSenderAS2ID)
  {
    m_sSenderAS2ID = sSenderAS2ID;
    return this;
  }

  /**
   * Set the email address of the sender. This is required for the AS2 protocol
   * but not (to my knowledge) used in Peppol.
   *
   * @param sSenderAS2Email
   *        The email address of the sender. May not be <code>null</code> and
   *        must be a valid email address.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSenderAS2Email (@Nullable final String sSenderAS2Email)
  {
    m_sSenderAS2Email = sSenderAS2Email;
    return this;
  }

  /**
   * Set the key alias of the sender's key in the key store. For Peppol the key
   * alias of the sender should be identical to the AS2 sender ID (
   * {@link #setSenderAS2ID(String)}), so it should also start with "APP_" or
   * "PKD" (I think case insensitive for PKCS12 key stores).
   *
   * @param sSenderAS2KeyAlias
   *        The sender key alias to be used. May not be <code>null</code>.
   * @return this for chaining
   * @see #setKeyStore(IKeyStoreType, byte[], String)
   * @see #setKeyStore(IKeyStoreType, File, String)
   */
  @Nonnull
  public AS2ClientBuilder setSenderAS2KeyAlias (@Nullable final String sSenderAS2KeyAlias)
  {
    m_sSenderAS2KeyAlias = sSenderAS2KeyAlias;
    return this;
  }

  /**
   * Set the AS2 receiver ID (recipient ID). It is mapped to the "AS2-To"
   * header. For Peppol the AS2 receiver ID must be the common name (CN) of the
   * receiver's AP certificate subject (as determined by the SMP query).
   * Therefore it usually starts with "APP_".
   *
   * @param sReceiverAS2ID
   *        The AS2 receiver ID to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverAS2ID (@Nullable final String sReceiverAS2ID)
  {
    m_sReceiverAS2ID = sReceiverAS2ID;
    return this;
  }

  /**
   * Set the key alias of the receiver's key in the key store. For Peppol the
   * key alias of the receiver should be identical to the AS2 receiver ID (
   * {@link #setReceiverAS2ID(String)}), so it should also start with "APP_" or
   * "PKD" (I think case insensitive for PKCS12 key stores).
   *
   * @param sReceiverAS2KeyAlias
   *        The receiver key alias to be used. May not be <code>null</code>.
   * @return this for chaining
   * @see #setKeyStore(IKeyStoreType, byte[], String)
   * @see #setKeyStore(IKeyStoreType, File, String)
   */
  @Nonnull
  public AS2ClientBuilder setReceiverAS2KeyAlias (@Nullable final String sReceiverAS2KeyAlias)
  {
    m_sReceiverAS2KeyAlias = sReceiverAS2KeyAlias;
    return this;
  }

  /**
   * Set the AS2 endpoint URL of the receiver. This URL should be determined by
   * an SMP query.
   *
   * @param sReceiverAS2Url
   *        The AS2 endpoint URL of the receiver. This must be a valid URL. May
   *        not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverAS2Url (@Nullable final String sReceiverAS2Url)
  {
    m_sReceiverAS2Url = sReceiverAS2Url;
    return this;
  }

  /**
   * Set the public certificate of the receiver as determined by the SMP query.
   *
   * @param aReceiverCert
   *        The receiver certificate. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverCertificate (@Nullable final X509Certificate aReceiverCert)
  {
    m_aReceiverCert = aReceiverCert;
    return this;
  }

  /**
   * Set the handler for the AP certificate check result. By setting a custom
   * handler here, it is possible to (silently) ignore any certificate
   * verification errors and send anyway.
   *
   * @param aCertificateCheckResultHandler
   *        The receiver certificate check result handler. May not be
   *        <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverCertificateCheckResultHandler (@Nonnull final IAS2ClientBuilderCertificateCheckResultHandler aCertificateCheckResultHandler)
  {
    ValueEnforcer.notNull (aCertificateCheckResultHandler, "CertificateCheckResultHandler");
    m_aReceiverCertCheckResultHandler = aCertificateCheckResultHandler;
    return this;
  }

  /**
   * Set the algorithm to be used to sign AS2 messages. By default
   * {@link #DEFAULT_SIGNING_ALGORITHM} is used. An encryption algorithm cannot
   * be set because according to the Peppol AS2 specification the AS2 messages
   * may not be encrypted on a business level.
   *
   * @param eSigningAlgo
   *        The signing algorithm to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setAS2SigningAlgorithm (@Nullable final ECryptoAlgorithmSign eSigningAlgo)
  {
    m_eSigningAlgo = eSigningAlgo;
    return this;
  }

  /**
   * Set the abstract format for AS2 message IDs. By default
   * {@link #DEFAULT_AS2_MESSAGE_ID_FORMAT} is used so there is no need to
   * change it. The replacement of placeholders depends on the underlying AS2
   * library.
   *
   * @param sMessageIDFormat
   *        The message ID format to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setAS2MessageIDFormat (@Nullable final String sMessageIDFormat)
  {
    m_sMessageIDFormat = sMessageIDFormat;
    return this;
  }

  /**
   * Set the connection timeout in milliseconds.
   *
   * @param nConnectTimeoutMS
   *        Connect timeout milliseconds.
   * @return this for chaining
   * @see #getConnectTimeoutMS()
   * @since 2.0.2
   */
  @Nonnull
  public AS2ClientBuilder setConnectTimeoutMS (final int nConnectTimeoutMS)
  {
    m_nConnectTimeoutMS = nConnectTimeoutMS;
    return this;
  }

  /**
   * @return The connection timeout in milliseconds. The default value is
   *         {@link AS2ClientSettings#DEFAULT_CONNECT_TIMEOUT_MS}.
   * @since 2.0.2
   */
  public int getConnectTimeoutMS ()
  {
    return m_nConnectTimeoutMS;
  }

  /**
   * Set the read timeout in milliseconds.
   *
   * @param nReadTimeoutMS
   *        Read timeout milliseconds.
   * @return this for chaining
   * @see #getReadTimeoutMS()
   * @since 2.0.2
   */
  @Nonnull
  public AS2ClientBuilder setReadTimeoutMS (final int nReadTimeoutMS)
  {
    m_nReadTimeoutMS = nReadTimeoutMS;
    return this;
  }

  /**
   * @return The read timeout in milliseconds. The default value is
   *         {@link AS2ClientSettings#DEFAULT_READ_TIMEOUT_MS}.
   * @since 2.0.2
   */
  public int getReadTimeoutMS ()
  {
    return m_nReadTimeoutMS;
  }

  /**
   * Set the resource that represents the main business document to be
   * transmitted. It must be an XML document - other documents are not supported
   * by Peppol.<br>
   * Note: This should NOT be the SBDH as this is added internally.<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aBusinessDocument
   *        The file containing the business document to be set. May be
   *        <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setBusinessDocument (@Nonnull final File aBusinessDocument)
  {
    return setBusinessDocument (new FileSystemResource (aBusinessDocument));
  }

  /**
   * Set the resource that represents the main business document to be
   * transmitted. It must be an XML document - other documents are not supported
   * by Peppol.<br>
   * Note: This should NOT be the SBDH as this is added internally.<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aBusinessDocument
   *        The byte array content of the business document to be set. May be
   *        <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setBusinessDocument (@Nonnull final byte [] aBusinessDocument)
  {
    return setBusinessDocument (new ReadableResourceByteArray (aBusinessDocument));
  }

  /**
   * Set the resource that represents the main business document to be
   * transmitted. It must be an XML document - other documents are not supported
   * by Peppol.<br>
   * Note: This should NOT be the SBDH as this is added internally.<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aBusinessDocumentRes
   *        The resource pointing to the business document to be set. May be
   *        <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setBusinessDocument (@Nullable final IReadableResource aBusinessDocumentRes)
  {
    m_aBusinessDocumentRes = aBusinessDocumentRes;
    return this;
  }

  /**
   * Set the W3C Element that represents the main business document to be
   * transmitted.<br>
   * Note: This should NOT be the SBDH as this is added internally.<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aBusinessDocumentElement
   *        The business document to be set. May be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setBusinessDocument (@Nullable final Element aBusinessDocumentElement)
  {
    m_aBusinessDocumentElement = aBusinessDocumentElement;
    return this;
  }

  /**
   * Set the Peppol sender ID. This is your Peppol participant ID.
   *
   * @param aPeppolSenderID
   *        The sender Peppol participant ID. May be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolSenderID (@Nullable final IParticipantIdentifier aPeppolSenderID)
  {
    m_aPeppolSenderID = aPeppolSenderID;
    return this;
  }

  /**
   * Set the Peppol receiver ID. This is the Peppol participant ID of the
   * recipient.
   *
   * @param aPeppolReceiverID
   *        The receiver Peppol participant ID. May be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolReceiverID (@Nullable final IParticipantIdentifier aPeppolReceiverID)
  {
    m_aPeppolReceiverID = aPeppolReceiverID;
    return this;
  }

  /**
   * Set the Peppol document type identifier for the exchanged business
   * document.
   *
   * @param aPeppolDocumentTypeID
   *        The Peppol document type identifier. May be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolDocumentTypeID (@Nullable final IDocumentTypeIdentifier aPeppolDocumentTypeID)
  {
    m_aPeppolDocumentTypeID = aPeppolDocumentTypeID;
    return this;
  }

  /**
   * Set the Peppol process identifier for the exchanged business document.
   *
   * @param aPeppolProcessID
   *        The Peppol process identifier. May be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolProcessID (@Nullable final IProcessIdentifier aPeppolProcessID)
  {
    m_aPeppolProcessID = aPeppolProcessID;
    return this;
  }

  /**
   * Set the validation executor set ID to be used for validating the business
   * document before sending.<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aVESID
   *        The VESID to be used. May be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setValidationKey (@Nullable final VESID aVESID)
  {
    m_aVESID = aVESID;
    return this;
  }

  /**
   * Set the SMP client to be used. The SMP client can help to automatically
   * determine the following fields:
   * <ul>
   * <li>Receiver AS2 endpoint URL - {@link #setReceiverAS2Url(String)}</li>
   * <li>Receiver certificate - {@link #setReceiverCertificate(X509Certificate)}
   * </li>
   * <li>Receiver AS2 ID - {@link #setReceiverAS2ID(String)}</li>
   * </ul>
   * so that you need to call this method only if you did not set these values
   * previously. If any of the values mentioned above is already set, it's value
   * is not touched!
   * <p>
   * As a prerequisite to performing an SMP lookup, at least the following
   * properties must be set:
   * <ul>
   * <li>The Peppol receiver participant ID -
   * {@link #setPeppolReceiverID(IParticipantIdentifier)}</li>
   * <li>The Peppol document type ID -
   * {@link #setPeppolDocumentTypeID(IDocumentTypeIdentifier)}</li>
   * <li>The Peppol process ID - {@link #setPeppolProcessID(IProcessIdentifier)}
   * </li>
   * </ul>
   *
   * @param aSMPClient
   *        The SMP client to be used. May be <code>null</code> to indicate no
   *        SMP lookup necessary.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSMPClient (@Nullable final ISMPServiceMetadataProvider aSMPClient)
  {
    m_aSMPClient = aSMPClient;
    return this;
  }

  /**
   * Set the factory to create {@link AS2Client} objects internally. Overwrite
   * this if you need a proxy in the AS2Client object. By default a new instance
   * of AS2Client is created so you don't need to call this method.
   *
   * @param aAS2ClientFactory
   *        The factory to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setAS2ClientFactory (@Nonnull final ISupplier <AS2Client> aAS2ClientFactory)
  {
    m_aAS2ClientFactory = ValueEnforcer.notNull (aAS2ClientFactory, "AS2ClientFactory");
    return this;
  }

  /**
   * Set the custom namespace context to be used for marshalling the SBDH
   * document. By default the SBDH namespace URI {@link CSBDH#SBDH_NS} is mapped
   * to the default prefix (""). Prior to v3 it was mapped to the "sh" prefix
   * but that caused problems with certain Oxalis versions that scan for
   * <code>&lt;StandardBusinessDocument</code> in the incoming byte sequence
   * (which is a classical beginners error).<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aNamespaceContext
   *        The new namespace context to be used. May be <code>null</code> to
   *        indicate the usage of the default namespace context.
   * @return this for chaining
   * @since 2.0.5
   * @deprecated Use {@link #setSBDHNamespaceContext(INamespaceContext)} instead
   */
  @Deprecated
  @Nonnull
  public AS2ClientBuilder setNamespaceContext (@Nullable final INamespaceContext aNamespaceContext)
  {
    return setSBDHNamespaceContext (aNamespaceContext);
  }

  /**
   * Set the custom namespace context to be used for marshalling the SBDH
   * document. By default the SBDH namespace URI {@link CSBDH#SBDH_NS} is mapped
   * to the default prefix (""). Prior to v3 it was mapped to the "sh" prefix
   * but that caused problems with certain Oxalis versions that scan for
   * <code>&lt;StandardBusinessDocument</code> in the incoming byte sequence
   * (which is a classical beginners error).<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aSBDHNamespaceContext
   *        The new namespace context to be used. May be <code>null</code> to
   *        indicate the usage of the default namespace context.
   * @return this for chaining
   * @since 2.0.5
   */
  @Nonnull
  public AS2ClientBuilder setSBDHNamespaceContext (@Nullable final INamespaceContext aSBDHNamespaceContext)
  {
    m_aSBDHNamespaceContext = aSBDHNamespaceContext;
    return this;
  }

  /**
   * Set an optional consumer that takes the byte array representation of the
   * created StandardBusinessDocument. This is for logging purposes only. Please
   * note that the invocation of the callback has an impact on runtime
   * performance if the data handler is not used, since the conversion to a byte
   * array happens explicitly for this logging call. For big messages that may
   * make an impact.
   *
   * @param aSBDHBytesConsumer
   *        The optional consumer to use. May be <code>null</code>.
   * @return this for chaining
   * @see #setUseDataHandler(boolean)
   * @since 3.3.2
   */
  @Nonnull
  public AS2ClientBuilder setSBDHBytesConsumer (@Nullable final IConsumer <byte []> aSBDHBytesConsumer)
  {
    m_aSBDHBytesConsumer = aSBDHBytesConsumer;
    return this;
  }

  /**
   * Set a custom <code>Content-Transfer-Encoding</code> type. By default the
   * AS2-default 'binary' is used. This setting alters the way how the payload
   * is encoded inside the transmitted AS2 message and must usually not be set!
   *
   * @param eCTE
   *        The new content transfer encoding to be used. May not be
   *        <code>null</code>.
   * @return this for chaining
   * @since 2.0.7
   */
  @Nonnull
  public AS2ClientBuilder setContentTransferEncoding (@Nonnull final EContentTransferEncoding eCTE)
  {
    ValueEnforcer.notNull (eCTE, "ContentTransferEncoding");
    m_eCTE = eCTE;
    return this;
  }

  /**
   * Set the handler for validation errors. By default an exception is thrown.
   * With the provided handler, you can change that behaviour and e.g. just log
   * it.<br>
   * Note: don't call this, if you have the SBDH already available.
   *
   * @param aValidationResultHandler
   *        The validation handler to be set. May not be <code>null</code>.
   * @return this for chaining
   * @since 3.0.7
   */
  @Nonnull
  public AS2ClientBuilder setValidatonResultHandler (@Nonnull final IAS2ClientBuilderValidatonResultHandler aValidationResultHandler)
  {
    ValueEnforcer.notNull (aValidationResultHandler, "ValidationResultHandler");
    m_aValidationResultHandler = aValidationResultHandler;
    return this;
  }

  /**
   * Set an optional dumper for the outgoing messages.<br>
   *
   * @param aHttpOutgoingDumper
   *        The dumper to be used. Pass <code>null</code> for no dumping (which
   *        is the default).
   * @return this for chaining
   * @since 3.0.7
   */
  @Nonnull
  public AS2ClientBuilder setOutgoingDumper (@Nullable final IHTTPOutgoingDumper aHttpOutgoingDumper)
  {
    return setOutgoingDumperFactory (aHttpOutgoingDumper == null ? null : aMsg -> aHttpOutgoingDumper);
  }

  /**
   * Set an optional dumper factory for the outgoing messages.<br>
   *
   * @param aHttpOutgoingDumperFactory
   *        The dumper factory to be used. Pass <code>null</code> for no dumping
   *        (which is the default).
   * @return this for chaining
   * @since 3.0.7
   */
  @Nonnull
  public AS2ClientBuilder setOutgoingDumperFactory (@Nullable final IHTTPOutgoingDumperFactory aHttpOutgoingDumperFactory)
  {
    m_aHttpOutgoingDumperFactory = aHttpOutgoingDumperFactory;
    return this;
  }

  /**
   * Set a custom incoming dumper for this request.
   *
   * @param aHttpIncomingDumper
   *        The dumper to be used. May be <code>null</code>.
   * @return this for chaining
   * @since 3.0.10
   */
  @Nonnull
  public AS2ClientBuilder setIncomingDumper (@Nullable final IHTTPIncomingDumper aHttpIncomingDumper)
  {
    m_aHttpIncomingDumper = aHttpIncomingDumper;
    return this;
  }

  /**
   * @return <code>true</code> if the data should be internally passed with a
   *         {@link DataHandler} or <code>false</code> if the data should be
   *         passed in as String. The default is <code>true</code>.
   * @since 3.0.10
   */
  public boolean isUseDataHandler ()
  {
    return m_bUseDataHandler;
  }

  /**
   * Use a {@link DataHandler} for transmission or a String? This method is an
   * internal method and should not be called except you are facing a very
   * specific problem as outlined in e.g.
   * https://github.com/phax/as2-lib/issues/45
   *
   * @param bUseDataHandler
   *        <code>true</code> to use the {@link DataHandler}, <code>false</code>
   *        to use the string.
   * @return this for chaining
   * @since 3.0.10
   */
  @Nonnull
  public AS2ClientBuilder setUseDataHandler (final boolean bUseDataHandler)
  {
    m_bUseDataHandler = bUseDataHandler;
    return this;
  }

  /**
   * @return The MIME type to be used for the payload. The default is
   *         {@link #DEFAULT_MIME_TYPE}. Never <code>null</code>.
   * @since 3.0.10
   */
  @Nonnull
  public IMimeType getMimeType ()
  {
    return m_aMimeType;
  }

  /**
   * Set the MIME type to be used. By default {@link #DEFAULT_MIME_TYPE} is
   * used.
   *
   * @param aMimeType
   *        The new MIME type to be used. May NOT be <code>null</code>.
   * @return this for chaining
   * @since 3.0.10
   */
  @Nonnull
  public AS2ClientBuilder setMimeType (@Nonnull final IMimeType aMimeType)
  {
    ValueEnforcer.notNull (aMimeType, "MimeType");
    m_aMimeType = aMimeType;
    return this;
  }

  /**
   * @return Get the transport profile ID used in the SMP lookup. By default
   *         this is Peppol AS2 v1, than Peppol AS2 v2. Never <code>null</code>.
   * @since v3.2.1
   */
  @Nonnull
  @Nonempty
  @ReturnsMutableCopy
  public ICommonsList <ISMPTransportProfile> getAllSMPTransportProfiles ()
  {
    return m_aTransportProfiles.getClone ();
  }

  /**
   * Set the SMP transport profiles to be used. This is needed if Peppol AS2 V2
   * should be prioritized over v1.
   *
   * @param aTransportProfiles
   *        The new SMP transport profiles to be used. The order of the array is
   *        the order of execution. May neither be <code>null</code> nor empty.
   * @return this for chaining
   * @since v3.2.1
   */
  @Nonnull
  public AS2ClientBuilder setSMPTransportProfiles (@Nonnull @Nonempty final ISMPTransportProfile... aTransportProfiles)
  {
    ValueEnforcer.notEmpty (aTransportProfiles, "TransportProfiles");
    m_aTransportProfiles.setAll (aTransportProfiles);
    return this;
  }

  /**
   * @return The consumer that is invoked when an SMP client lookup is performed
   *         and a transport profile was chosen. May be <code>null</code>.
   * @since v3.2.1
   */
  @Nullable
  public Consumer <ISMPTransportProfile> getSelectedTransportProfileConsumer ()
  {
    return m_aSelectedTransportProfileConsumer;
  }

  /**
   * @param aConsumer
   *        The consumer that is invoked when an SMP client lookup is performed
   *        and a transport profile was chosen. May be <code>null</code>.
   * @return this for chaining
   * @since v3.2.1
   */
  @Nonnull
  public AS2ClientBuilder setSelectedTransportProfileConsumer (@Nullable final Consumer <ISMPTransportProfile> aConsumer)
  {
    m_aSelectedTransportProfileConsumer = aConsumer;
    return this;
  }

  /**
   * This method is responsible for performing the SMP client lookup if an SMP
   * client was specified via
   * {@link #setSMPClient(ISMPServiceMetadataProvider)}. If any of the
   * prerequisites mentioned there is not fulfilled a warning is emitted via the
   * {@link #getMessageHandler()} and nothing happens. If all fields to be
   * determined by the SMP are already no SMP lookup is performed either. If the
   * SMP lookup fails, a warning is emitted and nothing happens.
   *
   * @throws AS2ClientBuilderException
   *         In case SMP client lookup triggers an unrecoverable error via the
   *         message handler
   */
  protected void performSMPClientLookup () throws AS2ClientBuilderException
  {
    if (m_aSMPClient != null)
    {
      // Check pre-requisites
      if (m_aPeppolReceiverID == null)
        getMessageHandler ().warn ("Cannot perform SMP lookup because the Peppol receiver ID is missing");
      else
        if (m_aPeppolDocumentTypeID == null)
          getMessageHandler ().warn ("Cannot perform SMP lookup because the Peppol document type ID is missing");
        else
          if (m_aPeppolProcessID == null)
            getMessageHandler ().warn ("Cannot perform SMP lookup because the Peppol process ID is missing");
          else
          {
            // All prerequisites are matched

            // Check if all fields to be determined are present, to avoid
            // unnecessary lookup calls.
            if (m_sReceiverAS2Url == null || m_aReceiverCert == null || m_sReceiverAS2ID == null)
            {
              // Perform the lookup.
              if (LOGGER.isDebugEnabled ())
                LOGGER.debug ("Performing SMP lookup for receiver '" +
                              m_aPeppolReceiverID.getURIEncoded () +
                              "' on document type '" +
                              m_aPeppolDocumentTypeID.getURIEncoded () +
                              "' and process ID '" +
                              m_aPeppolProcessID.getURIEncoded () +
                              "' using transport profiles " +
                              StringHelper.getImplodedMapped (", ", m_aTransportProfiles, ISMPTransportProfile::getID));

              SignedServiceMetadataType aServiceMetadata = null;
              try
              {
                aServiceMetadata = m_aSMPClient.getServiceMetadataOrNull (m_aPeppolReceiverID, m_aPeppolDocumentTypeID);
                if (aServiceMetadata == null)
                  if (LOGGER.isDebugEnabled ())
                    LOGGER.debug ("No such SMP service registration");
                  else
                    LOGGER.warn ("No such SMP service registration");
              }
              catch (final SMPClientException ex)
              {
                if (LOGGER.isDebugEnabled ())
                  LOGGER.debug ("Error querying the SMP", ex);
                else
                  LOGGER.error ("Error querying the SMP: " + ex.getMessage ());
                // Fall through
              }

              EndpointType aEndpoint = null;
              if (aServiceMetadata != null)
              {
                // Try to extract the endpoint from the service metadata
                for (final ISMPTransportProfile aTP : m_aTransportProfiles)
                {
                  aEndpoint = SMPClientReadOnly.getEndpoint (aServiceMetadata, m_aPeppolProcessID, aTP);
                  if (aEndpoint != null)
                  {
                    // Break after the first hit
                    if (LOGGER.isDebugEnabled ())
                      LOGGER.debug ("Using SMP endpoint using transport profile '" + aTP.getID () + "'");

                    // Call consumer
                    if (m_aSelectedTransportProfileConsumer != null)
                      m_aSelectedTransportProfileConsumer.accept (aTP);

                    break;
                  }
                }
              }

              // Interpret the result
              if (aEndpoint == null)
              {
                // No such SMP entry
                getMessageHandler ().error ("Failed to perform SMP lookup for receiver '" +
                                            m_aPeppolReceiverID.getURIEncoded () +
                                            "' on document type '" +
                                            m_aPeppolDocumentTypeID.getURIEncoded () +
                                            "' and process ID '" +
                                            m_aPeppolProcessID.getURIEncoded () +
                                            "' using transport profiles '" +
                                            StringHelper.getImplodedMapped (", ", m_aTransportProfiles, ISMPTransportProfile::getID) +
                                            ". " +
                                            (aServiceMetadata != null ? "The service metadata was gathered successfully but no endpoint was found."
                                                                      : "Failed to get the service metadata."));
              }
              else
              {
                // Extract from SMP response
                if (m_sReceiverAS2Url == null)
                  m_sReceiverAS2Url = SMPClientReadOnly.getEndpointAddress (aEndpoint);
                if (m_aReceiverCert == null)
                  try
                  {
                    m_aReceiverCert = SMPClientReadOnly.getEndpointCertificate (aEndpoint);
                  }
                  catch (final CertificateException ex)
                  {
                    getMessageHandler ().error ("Failed to build X.509 certificate from SMP client response", ex);
                  }

                // Verify the certificate
                {
                  final LocalDateTime aNow = PDTFactory.getCurrentLocalDateTime ();
                  final EPeppolCertificateCheckResult eCertCheckResult = PeppolCertificateChecker.checkPeppolAPCertificate (m_aReceiverCert,
                                                                                                                            aNow,
                                                                                                                            ETriState.UNDEFINED,
                                                                                                                            null);

                  // Interpret the result
                  m_aReceiverCertCheckResultHandler.onCertificateCheckResult (m_aReceiverCert, aNow, eCertCheckResult);
                }

                if (m_sReceiverAS2ID == null)
                  try
                  {
                    m_sReceiverAS2ID = PeppolCertificateHelper.getSubjectCN (m_aReceiverCert);
                  }
                  catch (final Exception ex)
                  {
                    getMessageHandler ().error ("Failed to get the Receiver AS ID from the provided certificate", ex);
                  }
              }
            }
            else
            {
              if (LOGGER.isDebugEnabled ())
                LOGGER.debug ("Not performing SMP lookup because all target fields are already set!");
            }
          }
    }
  }

  /**
   * Certain values can by convention be derived from other values. This happens
   * inside this method. There is no need to call this method manually, it is
   * called automatically before {@link #verifyContent()} is called.
   */
  @OverridingMethodsMustInvokeSuper
  protected void setDefaultDerivedValues ()
  {
    if (m_sReceiverAS2KeyAlias == null)
    {
      // No key alias is specified, so use the same as the receiver ID (which
      // may be null)
      m_sReceiverAS2KeyAlias = m_sReceiverAS2ID;
      if (LOGGER.isDebugEnabled ())
        LOGGER.debug ("The receiver AS2 key alias was defaulted to the AS2 receiver ID ('" + m_sReceiverAS2ID + "')");
    }
  }

  private void _verifyContent (final boolean bSendBusinessDocument) throws AS2ClientBuilderException
  {
    if (m_aKeyStoreType == null)
      m_aMessageHandler.error ("No AS2 key store type is defined");

    if (m_aKeyStoreFile != null)
    {
      if (!m_aKeyStoreFile.exists ())
        m_aMessageHandler.error ("The provided AS2 key store file '" + m_aKeyStoreFile.getAbsolutePath () + "' does not exist.");
      else
        if (!m_aKeyStoreFile.isFile ())
          m_aMessageHandler.error ("The provided AS2 key store file '" +
                                   m_aKeyStoreFile.getAbsolutePath () +
                                   "' is not a file but potentially a directory.");
        else
          if (!m_aKeyStoreFile.canWrite ())
            m_aMessageHandler.error ("The provided AS2 key store file '" +
                                     m_aKeyStoreFile.getAbsolutePath () +
                                     "' is not writable. As it is dynamically modified, it must be writable.");
    }
    else
      if (m_aKeyStoreBytes == null)
        m_aMessageHandler.error ("No AS2 key store is defined");

    if (m_sKeyStorePassword == null)
      m_aMessageHandler.error ("No AS2 key store password provided. If you need an empty password, please provide an empty String!");

    if (StringHelper.hasNoText (m_sAS2Subject))
      m_aMessageHandler.error ("The AS2 message subject is missing");

    if (StringHelper.hasNoText (m_sSenderAS2ID))
      m_aMessageHandler.error ("The AS2 sender ID is missing");
    else
      if (!m_sSenderAS2ID.startsWith (APP_PREFIX_V3))
        m_aMessageHandler.warn ("The AS2 sender ID '" +
                                m_sSenderAS2ID +
                                "' should start with '" +
                                APP_PREFIX_V3 +
                                "' as required by the Peppol specification");

    if (StringHelper.hasNoText (m_sSenderAS2Email))
      m_aMessageHandler.error ("The AS2 sender email address is missing");
    else
      if (!EmailAddressHelper.isValid (m_sSenderAS2Email))
        m_aMessageHandler.warn ("The AS2 sender email address '" + m_sSenderAS2Email + "' seems to be an invalid email address.");

    if (StringHelper.hasNoText (m_sSenderAS2KeyAlias))
      m_aMessageHandler.error ("The AS2 sender key alias is missing");
    else
      if (!m_sSenderAS2KeyAlias.startsWith (APP_PREFIX_V3))
        m_aMessageHandler.warn ("The AS2 sender key alias '" +
                                m_sSenderAS2KeyAlias +
                                "' should start with '" +
                                APP_PREFIX_V3 +
                                "' for the use with the dynamic AS2 partnerships");
      else
        if (m_sSenderAS2ID != null && !m_sSenderAS2ID.equals (m_sSenderAS2KeyAlias))
          m_aMessageHandler.warn ("The AS2 sender key alias ('" +
                                  m_sSenderAS2KeyAlias +
                                  "') should match the AS2 sender ID ('" +
                                  m_sSenderAS2ID +
                                  "')");

    if (StringHelper.hasNoText (m_sReceiverAS2ID))
      m_aMessageHandler.error ("The AS2 receiver ID is missing");
    else
      if (!m_sReceiverAS2ID.startsWith (APP_PREFIX_V3))
        m_aMessageHandler.warn ("The AS2 receiver ID '" +
                                m_sReceiverAS2ID +
                                "' should start with '" +
                                APP_PREFIX_V3 +
                                "' as required by the Peppol specification");

    if (StringHelper.hasNoText (m_sReceiverAS2KeyAlias))
      m_aMessageHandler.error ("The AS2 receiver key alias is missing");
    else
      if (!m_sReceiverAS2KeyAlias.startsWith (APP_PREFIX_V3))
        m_aMessageHandler.warn ("The AS2 receiver key alias '" +
                                m_sReceiverAS2KeyAlias +
                                "' should start with '" +
                                APP_PREFIX_V3 +
                                "' for the use with the dynamic AS2 partnerships");
      else
        if (m_sReceiverAS2ID != null && !m_sReceiverAS2ID.equals (m_sReceiverAS2KeyAlias))
          m_aMessageHandler.warn ("The AS2 receiver key alias ('" +
                                  m_sReceiverAS2KeyAlias +
                                  "') should match the AS2 receiver ID ('" +
                                  m_sReceiverAS2ID +
                                  "')");

    if (StringHelper.hasNoText (m_sReceiverAS2Url))
      m_aMessageHandler.error ("The AS2 receiver URL (AS2 endpoint URL) is missing");
    else
      if (URLHelper.getAsURL (m_sReceiverAS2Url) == null)
        m_aMessageHandler.warn ("The provided AS2 receiver URL '" + m_sReceiverAS2Url + "' seems to be an invalid URL");

    if (m_aReceiverCert == null)
      m_aMessageHandler.error ("The receiver X.509 certificate is missing. Usually this is extracted from the SMP response");

    if (m_eSigningAlgo == null)
      m_aMessageHandler.error ("The signing algorithm for the AS2 message is missing");

    if (StringHelper.hasNoText (m_sMessageIDFormat))
      m_aMessageHandler.error ("The AS2 message ID format is missing.");

    if (bSendBusinessDocument)
    {
      if (m_aBusinessDocumentRes == null && m_aBusinessDocumentElement == null)
        m_aMessageHandler.error ("The XML business document to be send is missing.");
      else
        if (m_aBusinessDocumentRes != null && !m_aBusinessDocumentRes.exists ())
          m_aMessageHandler.error ("The XML business document to be send '" + m_aBusinessDocumentRes.getPath () + "' does not exist.");
    }

    if (m_aPeppolSenderID == null)
      m_aMessageHandler.error ("The Peppol sender participant ID is missing");
    else
      if (!m_aPeppolSenderID.hasScheme (PeppolIdentifierHelper.DEFAULT_PARTICIPANT_SCHEME))
        m_aMessageHandler.warn ("The Peppol sender participant ID '" +
                                m_aPeppolSenderID.getURIEncoded () +
                                "' is using a non-standard scheme!");

    if (m_aPeppolReceiverID == null)
      m_aMessageHandler.error ("The Peppol receiver participant ID is missing");
    else
      if (!m_aPeppolReceiverID.hasScheme (PeppolIdentifierHelper.DEFAULT_PARTICIPANT_SCHEME))
        m_aMessageHandler.warn ("The Peppol receiver participant ID '" +
                                m_aPeppolReceiverID.getURIEncoded () +
                                "' is using a non-standard scheme!");

    if (m_aPeppolDocumentTypeID == null)
      m_aMessageHandler.error ("The Peppol document type ID is missing");
    else
      if (!m_aPeppolDocumentTypeID.hasScheme (PeppolIdentifierHelper.DOCUMENT_TYPE_SCHEME_BUSDOX_DOCID_QNS) &&
          !m_aPeppolDocumentTypeID.hasScheme (PeppolIdentifierHelper.DOCUMENT_TYPE_SCHEME_PEPPOL_DOCTYPE_WILDCARD))
        m_aMessageHandler.warn ("The Peppol document type ID '" +
                                m_aPeppolDocumentTypeID.getURIEncoded () +
                                "' is using a non-standard scheme!");

    if (m_aPeppolProcessID == null)
      m_aMessageHandler.error ("The Peppol process ID is missing");
    else
      if (!m_aPeppolProcessID.hasScheme (PeppolIdentifierHelper.DEFAULT_PROCESS_SCHEME))
        m_aMessageHandler.warn ("The Peppol process ID '" + m_aPeppolProcessID.getURIEncoded () + "' is using a non-standard scheme!");

    if (bSendBusinessDocument)
    {
      if (m_aVESID == null)
        m_aMessageHandler.warn ("The validation executor set ID determining the business document validation is missing. Therefore the outgoing business document is NOT validated!");
    }

    // Ensure that if a non-throwing message handler is installed, that the
    // sending is not performed!
    if (m_aMessageHandler.getErrorCount () > 0)
      throw new AS2ClientBuilderException ("Not all required fields are present so the Peppol AS2 client call can NOT be performed. See the message handler for details!");
  }

  /**
   * Verify the content of all contained fields so that all know issues are
   * captured before sending. This method is automatically called before the
   * message is send (see {@link #sendSynchronous()}). All verification warnings
   * and errors are handled via the message handler.
   *
   * @throws AS2ClientBuilderException
   *         In case the message handler throws an exception in case of an
   *         error.
   * @see #setMessageHandler(IAS2ClientBuilderMessageHandler)
   */
  public void verifyContent () throws AS2ClientBuilderException
  {
    _verifyContent (true);
  }

  /**
   * Create a new {@link ValidationExecutorSetRegistry} to be used for Peppol
   * validation. It's okay to create it once, and reuse it for all validations.
   *
   * @return the default {@link ValidationExecutorSetRegistry} used internally.
   *         Never <code>null</code>.
   * @since 3.1.0
   */
  @Nonnull
  public static ValidationExecutorSetRegistry <IValidationSourceXML> createDefaultValidationRegistry ()
  {
    final ValidationExecutorSetRegistry <IValidationSourceXML> aVESRegistry = new ValidationExecutorSetRegistry <> ();
    PeppolValidation.initStandard (aVESRegistry);
    PeppolValidation.initThirdParty (aVESRegistry);
    return aVESRegistry;
  }

  /**
   * Create a new {@link ValidationExecutorSetRegistry} to be used with this
   * client builder. By default the {@link PeppolValidation} artefacts are
   * contained. If additional artefacts like SimplerInvoicing or EN16931 is to
   * be used, this method must be overwritten! This method is only called once
   * per client to lazily initialize the respective member variable.
   *
   * @return The created {@link ValidationExecutorSetRegistry} and never
   *         <code>null</code>.
   * @see #createDefaultValidationRegistry()
   * @since 2.0.3
   */
  @OverrideOnDemand
  @Nonnull
  protected ValidationExecutorSetRegistry <IValidationSourceXML> createValidationRegistry ()
  {
    return createDefaultValidationRegistry ();
  }

  /**
   * Validate a business document based on the provided parameters.
   *
   * @param aVESRegistry
   *        Registry of VES. May not be <code>null</code>.
   * @param aVESID
   *        The VES ID to be used. May not be <code>null</code>.
   * @param aValidationResultHandler
   *        The result handler. May not be <code>null</code>.
   * @param aXML
   *        The XML DOM element to be validated. May not be <code>null</code>.
   * @throws AS2ClientBuilderException
   *         in case validation failed or something else goes wrong - depending
   *         on the result handler
   * @since 3.1.0
   */
  public static void validateBusinessDocument (@Nonnull final ValidationExecutorSetRegistry <IValidationSourceXML> aVESRegistry,
                                               @Nonnull final VESID aVESID,
                                               @Nonnull final IAS2ClientBuilderValidatonResultHandler aValidationResultHandler,
                                               @Nonnull final Element aXML) throws AS2ClientBuilderException
  {
    final IValidationExecutorSet <IValidationSourceXML> aVES = aVESRegistry.getOfID (aVESID);
    if (aVES == null)
      throw new AS2ClientBuilderException ("The validation executor set ID " + aVESID.getAsSingleID () + " is unknown!");

    final ValidationResultList aValidationResult = ValidationExecutionManager.executeValidation (aVES,
                                                                                                 ValidationSourceXML.create (null, aXML));
    if (aValidationResult.containsAtLeastOneError ())
    {
      aValidationResultHandler.onValidationErrors (aValidationResult);
      LOGGER.warn ("Continue to send AS2 message, although validation errors are contained!");
    }
    else
      aValidationResultHandler.onValidationSuccess (aValidationResult);
  }

  /**
   * Perform the standard Peppol validation of the outgoing business document
   * before sending takes place. In case validation fails, an exception is
   * thrown. The validation is configured using the validation key. This method
   * is only called, when a validation key was set.
   *
   * @param aXML
   *        The DOM Element with the business document to be validated.
   * @throws AS2ClientBuilderException
   *         In case the validation executor set ID is unknown.
   * @throws AS2ClientBuilderValidationException
   *         In case validation failed.
   * @see #setValidationKey(VESID)
   * @see #validateBusinessDocument(ValidationExecutorSetRegistry, VESID,
   *      IAS2ClientBuilderValidatonResultHandler, Element)
   */
  @OverrideOnDemand
  protected void validateOutgoingBusinessDocument (@Nonnull final Element aXML) throws AS2ClientBuilderException
  {
    if (m_aVESRegistry == null)
    {
      // Create lazily
      m_aVESRegistry = createValidationRegistry ();
    }
    validateBusinessDocument (m_aVESRegistry, m_aVESID, m_aValidationResultHandler, aXML);
  }

  /**
   * Create a {@link StandardBusinessDocument} out of the provided information
   *
   * @param aSenderID
   *        Sender participant ID. May not be <code>null</code>.
   * @param aReceiverID
   *        Receiver participant ID. May not be <code>null</code>.
   * @param aDocTypeID
   *        Document type ID. May not be <code>null</code>.
   * @param aProcID
   *        Process ID. May not be <code>null</code>.
   * @param sInstanceIdentifier
   *        Optional instance identifier. May be <code>null</code> in which case
   *        a random UUID is will be used.
   * @param sUBLVersion
   *        The UBL version to use. May be <code>null</code> in which case the
   *        default "2.1" will be used.
   * @param aPayloadElement
   *        The payload element to be included in the SBD. May not be
   *        <code>null</code>.
   * @return The ready made {@link StandardBusinessDocument} according to the
   *         Peppol needs. Never <code>null</code>.
   * @since 3.1.0
   */
  @Nonnull
  public static StandardBusinessDocument createSBDH (@Nonnull final IParticipantIdentifier aSenderID,
                                                     @Nonnull final IParticipantIdentifier aReceiverID,
                                                     @Nonnull final IDocumentTypeIdentifier aDocTypeID,
                                                     @Nonnull final IProcessIdentifier aProcID,
                                                     @Nullable final String sInstanceIdentifier,
                                                     @Nullable final String sUBLVersion,
                                                     @Nonnull final Element aPayloadElement)
  {
    final PeppolSBDHDocument aData = new PeppolSBDHDocument (PeppolIdentifierFactory.INSTANCE);
    aData.setSender (aSenderID.getScheme (), aSenderID.getValue ());
    aData.setReceiver (aReceiverID.getScheme (), aReceiverID.getValue ());
    aData.setDocumentType (aDocTypeID.getScheme (), aDocTypeID.getValue ());
    aData.setProcess (aProcID.getScheme (), aProcID.getValue ());
    aData.setDocumentIdentification (aPayloadElement.getNamespaceURI (),
                                     StringHelper.hasText (sUBLVersion) ? sUBLVersion : CPeppolSBDH.TYPE_VERSION_21,
                                     aPayloadElement.getLocalName (),
                                     StringHelper.hasText (sInstanceIdentifier) ? sInstanceIdentifier : UUID.randomUUID ().toString (),
                                     PDTFactory.getCurrentLocalDateTime ());
    aData.setBusinessMessage (aPayloadElement);
    return new PeppolSBDHDocumentWriter ().createStandardBusinessDocument (aData);
  }

  /**
   * Convert the passed {@link StandardBusinessDocument} to a serialized version
   * using the best serialization method.
   *
   * @param aSBD
   *        The {@link StandardBusinessDocument} to be validated. May not be
   *        <code>null</code>.
   * @param aNamespaceContext
   *        An optional namespace context for XML serialization to be used. May
   *        be <code>null</code> in which case a default namespace context will
   *        be used.
   * @return The create {@link NonBlockingByteArrayOutputStream} that contains
   *         the serialized XML. Never <code>null</code>.
   * @throws AS2ClientBuilderException
   *         in case serialization failed, because e.g. the
   *         {@link StandardBusinessDocument} is incomplete.
   * @since 3.1.0
   */
  @Nonnull
  public static NonBlockingByteArrayOutputStream getSerializedSBDH (@Nonnull final StandardBusinessDocument aSBD,
                                                                    @Nullable final INamespaceContext aNamespaceContext) throws AS2ClientBuilderException
  {
    // Version with huge memory consumption
    try (final NonBlockingByteArrayOutputStream aBAOS = new NonBlockingByteArrayOutputStream ())
    {
      final SBDMarshaller aSBDMarshaller = new SBDMarshaller ();

      // Set custom namespace context (work around an OpusCapita problem)
      if (aNamespaceContext != null)
        aSBDMarshaller.setNamespaceContext (aNamespaceContext);
      else
      {
        // Ensure default marshaller without a prefix is used!
        aSBDMarshaller.setNamespaceContext (new MapBasedNamespaceContext ().setDefaultNamespaceURI (CSBDH.SBDH_NS));
      }

      // Write to BAOS
      if (aSBDMarshaller.write (aSBD, aBAOS).isFailure ())
        throw new AS2ClientBuilderException ("Failed to serialize SBD!");
      return aBAOS;
    }
  }

  /**
   * @return The {@link AS2ClientSettings} to be used, based on the input
   *         parameters. Never <code>null</code>.
   */
  @Nonnull
  @OverridingMethodsMustInvokeSuper
  public AS2ClientSettings createAS2ClientSettings ()
  {
    // Start building the AS2 client settings
    final AS2ClientSettings aAS2ClientSettings = new AS2ClientSettings ();
    // Key store
    if (m_aKeyStoreFile != null)
      aAS2ClientSettings.setKeyStore (m_aKeyStoreType, m_aKeyStoreFile, m_sKeyStorePassword);
    else
      aAS2ClientSettings.setKeyStore (m_aKeyStoreType, m_aKeyStoreBytes, m_sKeyStorePassword);
    aAS2ClientSettings.setSaveKeyStoreChangesToFile (m_bSaveKeyStoreChangesToFile);

    // Fixed sender
    aAS2ClientSettings.setSenderData (m_sSenderAS2ID, m_sSenderAS2Email, m_sSenderAS2KeyAlias);

    // Dynamic receiver
    aAS2ClientSettings.setReceiverData (m_sReceiverAS2ID, m_sReceiverAS2KeyAlias, m_sReceiverAS2Url);
    aAS2ClientSettings.setReceiverCertificate (m_aReceiverCert);

    // AS2 stuff - no need to change anything in this block
    aAS2ClientSettings.setPartnershipName (aAS2ClientSettings.getSenderAS2ID () + "-" + aAS2ClientSettings.getReceiverAS2ID ());
    aAS2ClientSettings.setMDNOptions (new DispositionOptions ().setMICAlg (m_eSigningAlgo)
                                                               .setMICAlgImportance (DispositionOptions.IMPORTANCE_REQUIRED)
                                                               .setProtocol (DispositionOptions.PROTOCOL_PKCS7_SIGNATURE)
                                                               .setProtocolImportance (DispositionOptions.IMPORTANCE_REQUIRED));
    aAS2ClientSettings.setEncryptAndSign (null, m_eSigningAlgo);
    aAS2ClientSettings.setMessageIDFormat (m_sMessageIDFormat);

    aAS2ClientSettings.setConnectTimeoutMS (m_nConnectTimeoutMS);
    aAS2ClientSettings.setReadTimeoutMS (m_nReadTimeoutMS);

    aAS2ClientSettings.setHttpOutgoingDumperFactory (m_aHttpOutgoingDumperFactory);
    aAS2ClientSettings.setHttpIncomingDumper (m_aHttpIncomingDumper);

    // Add a custom header to request an MDN for IBM implementation
    aAS2ClientSettings.customHeaders ().addHeader (CHttpHeader.DISPOSITION_NOTIFICATION_TO, "dummy");

    return aAS2ClientSettings;
  }

  /**
   * This is the main sending routine. It performs the following steps:
   * <ol>
   * <li>Verify that all required parameters are present and valid -
   * {@link #verifyContent()}</li>
   * <li>The business document is read as XML. In case of an error, an exception
   * is thrown.</li>
   * <li>The Standard Business Document (SBD) is created, all Peppol required
   * fields are set and the business document is embedded.</li>
   * <li>The SBD is serialized and send via AS2</li>
   * <li>The AS2 response incl. the MDN is returned for further evaluation.</li>
   * </ol>
   *
   * @return The AS2 response returned by the AS2 sender. This is never
   *         <code>null</code>.
   * @throws AS2ClientBuilderException
   *         In case the the business document is invalid XML or in case
   *         {@link #verifyContent()} throws an exception because of invalid or
   *         incomplete settings.
   * @see #sendSynchronousSBDH(NonBlockingByteArrayOutputStream) in case you
   *      have the SBDH ready
   */
  @Nonnull
  public AS2ClientResponse sendSynchronous () throws AS2ClientBuilderException
  {
    // Perform SMP client lookup
    performSMPClientLookup ();

    // Set derivable values
    setDefaultDerivedValues ();

    // Verify the whole data set
    verifyContent ();

    // Build message

    // 1. read business document into memory - this may be a bottleneck!
    Element aBusinessDocumentXML = null;
    if (m_aBusinessDocumentRes != null)
    {
      final Document aXMLDocument = DOMReader.readXMLDOM (m_aBusinessDocumentRes);
      if (aXMLDocument == null)
        throw new AS2ClientBuilderException ("Failed to read business document '" + m_aBusinessDocumentRes.getPath () + "' as XML");
      aBusinessDocumentXML = aXMLDocument.getDocumentElement ();
      LOGGER.info ("Successfully parsed the business document");
    }
    else
    {
      aBusinessDocumentXML = m_aBusinessDocumentElement;
    }
    if (aBusinessDocumentXML == null)
      throw new AS2ClientBuilderException ("No XML business content present!");

    // 2. validate the business document
    if (m_aVESID != null)
      validateOutgoingBusinessDocument (aBusinessDocumentXML);

    // 3. build Peppol SBDH data
    final StandardBusinessDocument aSBD = createSBDH (m_aPeppolSenderID,
                                                      m_aPeppolReceiverID,
                                                      m_aPeppolDocumentTypeID,
                                                      m_aPeppolProcessID,
                                                      null,
                                                      null,
                                                      aBusinessDocumentXML);

    // 4. set client properties
    final AS2ClientSettings aAS2ClientSettings = createAS2ClientSettings ();

    final AS2ClientRequest aRequest = new AS2ClientRequest (m_sAS2Subject);

    // 5. assemble and send
    // Version with huge memory consumption
    final NonBlockingByteArrayOutputStream aBAOS = getSerializedSBDH (aSBD, m_aSBDHNamespaceContext);
    if (m_bUseDataHandler)
    {
      // Use data to force the usage of "application/xml" Content-Type in the
      // DataHandler

      // Convert to byte[] once
      final byte [] aSBDHBytes = aBAOS.toByteArray ();

      if (m_aSBDHBytesConsumer != null)
        m_aSBDHBytesConsumer.accept (aSBDHBytes);

      aRequest.setData (new DataHandler (aSBDHBytes, m_aMimeType.getAsString ()));
    }
    else
    {
      if (m_aSBDHBytesConsumer != null)
      {
        // Convert to byte[] only for the callback
        m_aSBDHBytesConsumer.accept (aBAOS.toByteArray ());
      }

      // Using a String is better when having a
      // com.sun.xml.ws.encoding.XmlDataContentHandler installed!
      aRequest.setData (aBAOS.getAsString (StandardCharsets.UTF_8), StandardCharsets.UTF_8);

      // Explicitly add application/xml even though the "setData" may have
      // suggested something else (like text/plain)
      aRequest.setContentType (m_aMimeType.getAsString ());
    }

    // Set the custom content transfer encoding
    aRequest.setContentTransferEncoding (m_eCTE);

    final AS2Client aAS2Client = m_aAS2ClientFactory.get ();
    if (false)
    {
      // Local Fiddler proxy
      aAS2Client.setHttpProxy (new Proxy (Proxy.Type.HTTP, new InetSocketAddress ("127.0.0.1", 8888)));
    }

    final AS2ClientResponse aResponse = aAS2Client.sendSynchronous (aAS2ClientSettings, aRequest);
    return aResponse;
  }

  /**
   * This is an alternative sending routine that assumes than an SBDH is already
   * available. It performs the following steps:
   * <ol>
   * <li>Verify that all required parameters are present and valid -
   * {@link #verifyContent()}</li>
   * <li>The SBD is send via AS2</li>
   * <li>The AS2 response incl. the MDN is returned for further evaluation.</li>
   * </ol>
   *
   * @param aBAOS
   *        The serialized SBDH. May not be <code>null</code>.
   * @return The AS2 response returned by the AS2 sender. This is never
   *         <code>null</code>.
   * @throws AS2ClientBuilderException
   *         In case the the business document is invalid XML or in case
   *         {@link #verifyContent()} throws an exception because of invalid or
   *         incomplete settings.
   * @see #sendSynchronous() if you don't have the SBDH
   * @since 3.1.0
   */
  @Nonnull
  public AS2ClientResponse sendSynchronousSBDH (@Nonnull final NonBlockingByteArrayOutputStream aBAOS) throws AS2ClientBuilderException
  {
    ValueEnforcer.notNull (aBAOS, "BAOS");

    // Perform SMP client lookup
    performSMPClientLookup ();

    // Set derivable values
    setDefaultDerivedValues ();

    // Verify the whole data set (but without the specific payload stuff)
    _verifyContent (false);

    // 4. set client properties
    final AS2ClientSettings aAS2ClientSettings = createAS2ClientSettings ();

    final AS2ClientRequest aRequest = new AS2ClientRequest (m_sAS2Subject);

    // 5. assemble and send
    if (m_bUseDataHandler)
    {
      // Use data to force the usage of "application/xml" Content-Type in the
      // DataHandler
      aRequest.setData (new DataHandler (aBAOS.toByteArray (), m_aMimeType.getAsString ()));
    }
    else
    {
      // Using a String is better when having a
      // com.sun.xml.ws.encoding.XmlDataContentHandler installed!
      aRequest.setData (aBAOS.getAsString (StandardCharsets.UTF_8), StandardCharsets.UTF_8);

      // Explicitly add application/xml even though the "setData" may have
      // suggested something else (like text/plain)
      aRequest.setContentType (m_aMimeType.getAsString ());
    }

    // Set the custom content transfer encoding
    aRequest.setContentTransferEncoding (m_eCTE);

    final AS2Client aAS2Client = m_aAS2ClientFactory.get ();
    final AS2ClientResponse aResponse = aAS2Client.sendSynchronous (aAS2ClientSettings, aRequest);
    return aResponse;
  }
}
