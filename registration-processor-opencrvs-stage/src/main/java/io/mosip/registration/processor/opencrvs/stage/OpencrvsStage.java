package io.mosip.registration.processor.opencrvs.stage;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.core.exception.ServiceError;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.abstractverticle.MosipEventBus;
import io.mosip.registration.processor.core.abstractverticle.MosipRouter;
import io.mosip.registration.processor.core.abstractverticle.MosipVerticleAPIManager;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.RegistrationTransactionStatusCode;
import io.mosip.registration.processor.core.common.rest.dto.ErrorDTO;
import io.mosip.registration.processor.core.constant.IdType;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.constant.ProviderStageName;
import io.mosip.registration.processor.core.constant.VidType;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.http.RequestWrapper;
import io.mosip.registration.processor.core.http.ResponseWrapper;
import io.mosip.registration.processor.core.idrepo.dto.CredentialRequestDto;
import io.mosip.registration.processor.core.idrepo.dto.CredentialResponseDto;
import io.mosip.registration.processor.core.idrepo.dto.VidInfoDTO;
import io.mosip.registration.processor.core.idrepo.dto.VidsInfosDTO;
import io.mosip.registration.processor.core.logger.LogDescription;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.core.status.util.StatusUtil;
import io.mosip.registration.processor.core.status.util.TrimExceptionMessage;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.opencrvs.constants.Constants;
import io.mosip.registration.processor.opencrvs.constants.MessageBusConstants;
import io.mosip.registration.processor.opencrvs.constants.OpencrvsStatusCode;
import io.mosip.registration.processor.opencrvs.exception.OpencrvsErrorMessages;
import io.mosip.registration.processor.opencrvs.exception.OpencrvsSuccessMessages;
import io.mosip.registration.processor.opencrvs.stage.exception.VidNotAvailableException;
import io.mosip.registration.processor.packet.storage.utils.PriorityBasedPacketManagerService;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

@Service
@Configuration
@EnableScheduling
@ComponentScan(basePackages = { "io.mosip.kernel.auth.defaultadapter",
		"io.mosip.registration.processor.core.config",
		"io.mosip.registration.processor.stages.config",
		"io.mosip.registration.processor.opencrvs.config",
		"io.mosip.registrationprocessor.stages.config",
		"io.mosip.registration.processor.status.config",
		"io.mosip.registration.processor.rest.client.config",
		"io.mosip.registration.processor.packet.storage.config",
		"io.mosip.registration.processor.packet.manager.config",
		"io.mosip.kernel.idobjectvalidator.config",
		"io.mosip.registration.processor.core.kernel.beans" })
public class OpencrvsStage extends MosipVerticleAPIManager {

	private static final String STAGE_PROPERTY_PREFIX = "mosip.regproc.opencrvs.";
	private Random sr = null;
	private static final int max = 999999;
	private static final int min = 100000;

	/** The Constant FILE_SEPARATOR. */
	public static final String FILE_SEPARATOR = File.separator;


	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(OpencrvsStage.class);

	/** The cluster manager url. */
	@Value("${vertx.cluster.configuration}")
	private String clusterManagerUrl;


	/** The core audit request builder. */
	@Autowired
	private AuditLogRequestBuilder auditLogRequestBuilder;

	/** The mosip event bus. */
	private MosipEventBus mosipEventBus;

	/** The registration status service. */
	@Autowired
	RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** worker pool size. */
	@Value("${worker.pool.size}")
	private Integer workerPoolSize;

	/** After this time intervel, message should be considered as expired (In seconds). */
	@Value("${"+STAGE_PROPERTY_PREFIX+"message.expiry-time-limit}")
	private Long messageExpiryTimeLimit;


	@Value("${mosip.registration.processor.encrypt:false}")
	private boolean encrypt;


	/** Mosip router for APIs */
	@Autowired
	MosipRouter router;

	boolean isConnection = false;

	private static final String SEPERATOR = "::";

	@Autowired
	private RegistrationProcessorRestClientService<Object> restClientService;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private Environment env;

	private static final String DATETIME_PATTERN = "mosip.registration.processor.datetime.pattern";

	@Autowired
	private PriorityBasedPacketManagerService packetManagerService;

	@Autowired
	private Utilities utilities;

	@Override
	protected String getPropertyPrefix() {
		return STAGE_PROPERTY_PREFIX;
	}

	/**
	 * Deploy verticle.
	 */
	public void deployVerticle() {

		mosipEventBus = this.getEventBus(this, clusterManagerUrl, workerPoolSize);
		this.consumeAndSend(mosipEventBus, MessageBusConstants.OPENCRVS_STAGE_BUS_IN, MessageBusConstants.OPENCRVS_STAGE_BUS_OUT,
				messageExpiryTimeLimit);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * io.mosip.registration.processor.core.spi.eventbus.EventBusManager#process(
	 * java.lang.Object)
	 */
	@Override
	public MessageDTO process(MessageDTO object) {
		TrimExceptionMessage trimeExpMessage = new TrimExceptionMessage();
		object.setMessageBusAddress(MessageBusConstants.OPENCRVS_STAGE_BUS_IN);
		object.setInternalError(Boolean.FALSE);
		object.setIsValid(Boolean.FALSE);
		LogDescription description = new LogDescription();

		boolean isTransactionSuccessful = false;
		String uin = null;
		String regId = object.getRid();
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				regId, "OpencrvsStage::process()::entry");

		InternalRegistrationStatusDto registrationStatusDto = null;
		RequestWrapper<CredentialRequestDto> requestWrapper = new RequestWrapper<>();
		ResponseWrapper<?> responseWrapper;
		CredentialResponseDto credentialResponseDto;
		try {
			registrationStatusDto = registrationStatusService.getRegistrationStatus(
					regId, object.getReg_type(), object.getIteration(), object.getWorkflowInstanceId());
			registrationStatusDto
					.setLatestTransactionTypeCode(Constants.OPENCRVS_TRANSACTION_TYPE);
			registrationStatusDto.setRegistrationStageName(getStageName());
			JSONObject jsonObject = utilities.retrieveIdrepoJson(regId);
			uin = JsonUtil.getJSONValue(jsonObject, IdType.UIN.toString());
			if (uin == null) {
				regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), null,
						OpencrvsErrorMessages.RPR_OPC_UIN_NOT_FOUND_IN_DATABASE.name());
				object.setIsValid(Boolean.FALSE);
				isTransactionSuccessful = false;
				description.setMessage(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getMessage());
				description.setCode(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getCode());

				registrationStatusDto.setStatusComment(
						StatusUtil.UIN_NOT_FOUND_IN_DATABASE.getMessage());
				registrationStatusDto.setSubStatusCode(StatusUtil.UIN_NOT_FOUND_IN_DATABASE.getCode());
				registrationStatusDto
						.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.FAILED.toString());
				registrationStatusDto
						.setLatestTransactionTypeCode(Constants.OPENCRVS_TRANSACTION_TYPE);

			}
			else {
				String vid = getVid(uin);
				String opencrvsBRN = getOpencrvsBRN(regId, registrationStatusDto);

				CredentialRequestDto credentialRequestDto = getCredentialRequestDto(vid);
				if(opencrvsBRN!=null && !opencrvsBRN.isEmpty()){
					Map<String, Object> additionalData = new HashMap<>();
					additionalData.put("opencrvsBRN",opencrvsBRN);
					credentialRequestDto.setAdditionalData(additionalData);
				}
				requestWrapper.setId(env.getProperty("mosip.registration.processor.credential.request.service.id"));
				requestWrapper.setRequest(credentialRequestDto);
				DateTimeFormatter format = DateTimeFormatter.ofPattern(env.getProperty(DATETIME_PATTERN));
				LocalDateTime localdatetime = LocalDateTime
						.parse(DateUtils.getUTCCurrentDateTimeString(env.getProperty(DATETIME_PATTERN)), format);
				requestWrapper.setRequesttime(localdatetime);
				requestWrapper.setVersion("1.0");
				responseWrapper = (ResponseWrapper<?>) restClientService.postApi(ApiName.CREDENTIALREQUEST, null, null,
						requestWrapper, ResponseWrapper.class, MediaType.APPLICATION_JSON);
				if (responseWrapper.getErrors() != null && !responseWrapper.getErrors().isEmpty()) {
					ErrorDTO error = responseWrapper.getErrors().get(0);
					object.setIsValid(Boolean.FALSE);
					isTransactionSuccessful = false;
					description.setMessage(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getMessage());
					description.setCode(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getCode());

					registrationStatusDto.setStatusComment(
							OpencrvsStatusCode.OPENCRVS_REQUEST_FAILED.getMessage() + SEPERATOR + error.getMessage());
					registrationStatusDto.setSubStatusCode(OpencrvsStatusCode.OPENCRVS_REQUEST_FAILED.getCode());
					registrationStatusDto
							.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.REPROCESS.toString());
					registrationStatusDto
							.setLatestTransactionTypeCode(Constants.OPENCRVS_TRANSACTION_TYPE);
				} else {
					credentialResponseDto = mapper.readValue(mapper.writeValueAsString(responseWrapper.getResponse()),
							CredentialResponseDto.class);

					registrationStatusDto.setRefId(credentialResponseDto.getRequestId());
					object.setIsValid(Boolean.TRUE);
					isTransactionSuccessful = true;
					description.setMessage(OpencrvsSuccessMessages.RPR_OPENCRVS_STAGE_REQUEST_SUCCESS.getMessage());
					description.setCode(OpencrvsSuccessMessages.RPR_OPENCRVS_STAGE_REQUEST_SUCCESS.getCode());
					registrationStatusDto.setStatusComment(
							trimeExpMessage.trimExceptionMessage(OpencrvsStatusCode.OPENCRVS_REQUEST_SUCCESS.getMessage()));
					registrationStatusDto.setSubStatusCode(OpencrvsStatusCode.OPENCRVS_REQUEST_SUCCESS.getCode());
					registrationStatusDto
							.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.PROCESSED.toString());
					registrationStatusDto
							.setLatestTransactionTypeCode(Constants.OPENCRVS_TRANSACTION_TYPE);

					regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
							LoggerFileConstant.REGISTRATIONID.toString(), regId, "OpencrvsStage::process()::exit");
				}
			}
		} catch (ApisResourceAccessException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					regId, OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.name() + e.getMessage()
							+ ExceptionUtils.getStackTrace(e));
			registrationStatusDto
					.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.REPROCESS.toString());
			registrationStatusDto.setStatusComment(trimeExpMessage.trimExceptionMessage(
					StatusUtil.API_RESOUCE_ACCESS_FAILED.getMessage() + SEPERATOR + e.getMessage()));
			registrationStatusDto.setSubStatusCode(StatusUtil.API_RESOUCE_ACCESS_FAILED.getCode());
			description.setMessage(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getMessage());
			description.setCode(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getCode());
			object.setInternalError(Boolean.TRUE);
		} catch (IOException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					regId, OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.name() + e.getMessage()
							+ ExceptionUtils.getStackTrace(e));
			registrationStatusDto
					.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.REPROCESS.toString());
			registrationStatusDto.setStatusComment(
					trimeExpMessage.trimExceptionMessage(StatusUtil.IO_EXCEPTION.getMessage() + e.getMessage()));
			registrationStatusDto.setSubStatusCode(StatusUtil.IO_EXCEPTION.getCode());
			description.setMessage(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getMessage());
			description.setCode(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getCode());
			object.setInternalError(Boolean.TRUE);
		} catch (VidNotAvailableException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					regId, OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.name() + e.getMessage()
							+ ExceptionUtils.getStackTrace(e));
			registrationStatusDto
					.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.REPROCESS.toString());
			registrationStatusDto.setStatusComment(
					trimeExpMessage.trimExceptionMessage(StatusUtil.VID_NOT_AVAILABLE.getMessage()));
			registrationStatusDto.setSubStatusCode(StatusUtil.VID_NOT_AVAILABLE.getCode());
			description.setMessage(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getMessage());
			description.setCode(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getCode());
			object.setInternalError(Boolean.TRUE);
		} catch (Exception e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					regId, OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.name() + e.getMessage()
							+ ExceptionUtils.getStackTrace(e));
			registrationStatusDto
					.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.REPROCESS.toString());
			registrationStatusDto.setStatusComment(
					trimeExpMessage.trimExceptionMessage(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getMessage()));
			registrationStatusDto.setSubStatusCode(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getCode());
			description.setMessage(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getMessage());
			description.setCode(OpencrvsErrorMessages.RPR_OPC_OPENCRVS_REQUEST_FAILED.getCode());
			object.setInternalError(Boolean.TRUE);
		}
		finally {
			if (object.getInternalError()) {
				updateErrorFlags(registrationStatusDto, object);
			}
			String eventId = "";
			String eventName = "";
			String eventType = "";
			eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
			eventName = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventName.UPDATE.toString()
					: EventName.EXCEPTION.toString();
			eventType = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventType.BUSINESS.toString()
					: EventType.SYSTEM.toString();
			/** Module-Id can be Both Success/Error code */
			String moduleId = isTransactionSuccessful
					? OpencrvsSuccessMessages.RPR_OPENCRVS_STAGE_REQUEST_SUCCESS.getCode()
					: description.getCode();
			String moduleName = Constants.MODULE_NAME_OPENCRVS;
			registrationStatusService.updateRegistrationStatus(registrationStatusDto, moduleId, moduleName);

			auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage(), eventId, eventName, eventType,
					moduleId, moduleName, regId);

		}

		return object;
	}

	private CredentialRequestDto getCredentialRequestDto(String regId) {
		CredentialRequestDto credentialRequestDto = new CredentialRequestDto();

		credentialRequestDto.setCredentialType(env.getProperty(STAGE_PROPERTY_PREFIX+"credentialtype"));
		credentialRequestDto.setEncrypt(encrypt);

		credentialRequestDto.setId(regId);

		credentialRequestDto.setIssuer(env.getProperty(STAGE_PROPERTY_PREFIX+"issuer"));

		credentialRequestDto.setEncryptionKey(generatePin());

		return credentialRequestDto;
	}


	/*
	 * (non-Javadoc)
	 *
	 * @see io.vertx.core.AbstractVerticle#start()
	 */
	@Override
	public void start() {
		router.setRoute(this.postUrl(getVertx(),
				MessageBusConstants.OPENCRVS_STAGE_BUS_IN, MessageBusConstants.OPENCRVS_STAGE_BUS_OUT));
		this.createServer(router.getRouter(), getPort());
	}

	public String generatePin() {
		if (sr == null)
			instantiate();
		int randomInteger = sr.nextInt(max - min) + min;
		return String.valueOf(randomInteger);
	}

	@SuppressWarnings("unchecked")
	private String getVid(String uin) throws ApisResourceAccessException, VidNotAvailableException {
		List<String> pathsegments = new ArrayList<>();
		pathsegments.add(uin);
		String vid = null;

		VidsInfosDTO vidsInfosDTO;

		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(), "",
				"OpencrvsStage::getVid():: get GETVIDSBYUIN service call started with request data : "
		);

		vidsInfosDTO =  (VidsInfosDTO) restClientService.getApi(ApiName.GETVIDSBYUIN,
				pathsegments, "", "", VidsInfosDTO.class);

		if (vidsInfosDTO.getErrors() != null && !vidsInfosDTO.getErrors().isEmpty()) {
			ServiceError error = vidsInfosDTO.getErrors().get(0);
			throw new VidNotAvailableException(OpencrvsErrorMessages.RPR_OPC_VID_NOT_AVAILABLE_EXCEPTION.getCode(),
					error.getMessage());

		} else {
			if(vidsInfosDTO.getResponse()!=null && !vidsInfosDTO.getResponse().isEmpty()) {
				for (VidInfoDTO VidInfoDTO : vidsInfosDTO.getResponse()) {
					if (VidType.PERPETUAL.name().equalsIgnoreCase(VidInfoDTO.getVidType())) {
						vid = VidInfoDTO.getVid();
						break;
					}
				}
				if (vid == null) {
					throw new VidNotAvailableException(
							OpencrvsErrorMessages.RPR_OPC_VID_NOT_AVAILABLE_EXCEPTION.getCode(),
							OpencrvsErrorMessages.RPR_OPC_VID_NOT_AVAILABLE_EXCEPTION.getMessage());
				}
				regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), "",
						"OpencrvsStage::getVid():: get GETVIDSBYUIN service call ended successfully");

			}else {
				throw new VidNotAvailableException(OpencrvsErrorMessages.RPR_OPC_VID_NOT_AVAILABLE_EXCEPTION.getCode(),
						OpencrvsErrorMessages.RPR_OPC_VID_NOT_AVAILABLE_EXCEPTION.getMessage());
			}

		}

		return vid;
	}

	private void updateErrorFlags(InternalRegistrationStatusDto registrationStatusDto, MessageDTO object) {
		object.setInternalError(true);
		if (registrationStatusDto.getLatestTransactionStatusCode()
				.equalsIgnoreCase(RegistrationTransactionStatusCode.REPROCESS.toString())) {
			object.setIsValid(true);
		} else {
			object.setIsValid(false);
		}
	}

	@Scheduled(fixedDelayString = "${mosip.regproc.opencrvsstage.pingeneration.refresh.millisecs:1800000}",
			initialDelayString = "${mosip.regproc.opencrvsstage.pingeneration.refresh.delay-on-startup.millisecs:5000}")
	public void instantiate() {
		regProcLogger.debug("Instantiating SecureRandom for credential pin generation............");
		try {
			sr = SecureRandom.getInstance("SHA1PRNG");
		} catch (NoSuchAlgorithmException e) {
			regProcLogger.error("Could not instantiate SecureRandom for credential pin generation", e);
		}
	}

	private String getOpencrvsBRN(String regId, InternalRegistrationStatusDto registrationStatusDto){
		// giving stageName as PACKET_VALIDATOR but it doesnt matter, as long as packetmanager.provider.packetvalidator is not present in regproc properties
		try{
			Map<String,String> metaInfo = packetManagerService.getMetaInfo(regId, registrationStatusDto.getRegistrationType(), ProviderStageName.PACKET_VALIDATOR);
			JSONArray metadata = new JSONArray(metaInfo.get("metaData"));
			for(int i=0;i<metadata.length();i++){
				if(metadata.getJSONObject(i).getString("label").equalsIgnoreCase("opencrvsBRN")){
					regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(), regId, "OpencrvsBRN obtained.");
					return metadata.getJSONObject(i).getString("value");
				}
			}
		} catch (Exception e){
			regProcLogger.warn(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(), regId, "Failed opencrvsBRN obtained. Exception: " + ExceptionUtils.getStackTrace(e));
			return null;
		}
		regProcLogger.warn(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(), regId, "Failed opencrvsBRN obtained. Not Found.");
		return null;
	}
}
