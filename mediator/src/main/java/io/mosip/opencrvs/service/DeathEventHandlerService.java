package io.mosip.opencrvs.service;

import java.util.Collections;
import java.util.List;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import io.mosip.kernel.core.exception.BaseCheckedException;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.opencrvs.constant.ApiName;
import io.mosip.opencrvs.constant.Constants;
import io.mosip.opencrvs.constant.LoggingConstants;
import io.mosip.opencrvs.dto.BaseEventRequest;
import io.mosip.opencrvs.dto.DecryptedEventDto;
import io.mosip.opencrvs.error.ErrorCode;
import io.mosip.opencrvs.util.LogUtil;
import io.mosip.opencrvs.util.OpencrvsDataUtil;
import io.mosip.opencrvs.util.RestTokenUtil;

@Service
public class DeathEventHandlerService {

    private static final Logger LOGGER = LogUtil.getLogger(DeathEventHandlerService.class);

    @Value("${mosip.iam.token_endpoint}")
    private String iamTokenEndpoint;
    @Value("${mosip.opencrvs.death.deactivate.status:DEACTIVATED}")
    private String deactivateStatus;
    @Value("${mosip.opencrvs.death.client.id}")
    private String deathClientId;
    @Value("${mosip.opencrvs.death.client.secret}")
    private String deathClientSecret;
    @Value("${mosip.kernel.uin.length}")
    private int uinLength;

    @Autowired
    private Environment env;

    @Autowired
    private Receiver receiver;

    @Autowired
    private RestTokenUtil restTokenUtil;

    @Autowired
    private OpencrvsDataUtil opencrvsDataUtil;

    public String handleEvent(BaseEventRequest request) throws BaseCheckedException {
        DecryptedEventDto decryptedEventDto = receiver.preProcess(request.getId(), request.toString());
        String uinVid = getUINFromDecryptedEvent(decryptedEventDto);
        String token = restTokenUtil.getOIDCToken(iamTokenEndpoint, deathClientId, deathClientSecret);
        String uin = getUINFromUINVID(uinVid, token);
        String rid;
        try{
            rid = opencrvsDataUtil.getRidFromBody(decryptedEventDto);
            if(rid == null || rid.isEmpty()) rid = receiver.generateDefaultRegistrationId();
        } catch(Exception e) {
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX, LoggingConstants.SESSION,LoggingConstants.ID,"DeathEvent:generateRid", "Couldnt generate rid", e);
            throw ErrorCode.RID_GENERATE_EXCEPTION.throwChecked(e);
        }

        try{
            String dateTimePattern = env.getProperty(Constants.DATETIME_PATTERN);
            String requestString = "{" +
                "\"id\": \"mosip.id.update\"," +
                "\"version\": \"v1.0\"," +
                "\"requesttime\": \"" + DateUtils.getUTCCurrentDateTimeString(dateTimePattern) + "\"," +
                "\"request\": {" +
                    "\"registrationId\": \"" + rid + "\"," +
                    "\"status\": \"" + deactivateStatus + "\"," +
                    "\"identity\": {" +
                        "\"IDSchemaVersion\": 0.0," +
                        "\"UIN\":\"" + uin + "\"" +
                    "}" +
                "}" +
            "}";
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Cookie", "Authorization="+token);
            HttpEntity<String> requestIdentity = new HttpEntity<>(requestString, headers);
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory());
            String response = restTemplate.patchForObject(env.getProperty(ApiName.IDREPO_IDENTITY), requestIdentity, String.class);
            LOGGER.info(LoggingConstants.SESSION, LoggingConstants.ID, "DeathEvent::deactivateUIN", "Response from Patch Identity : " + response);
            // handle proper errors from response here
        } catch (RestClientException rce) {
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX, LoggingConstants.SESSION, LoggingConstants.ID, "DeathEvent::deactivateUIN", "Error Occured", rce);
            throw ErrorCode.UIN_DEACTIVATE_ERROR_DEATH_EVENT.throwChecked(rce);
        }
        return rid;
    }

    public String getUINFromDecryptedEvent(DecryptedEventDto decryptedEventDto) throws BaseCheckedException{
        try{
            List<DecryptedEventDto.Event.Context.Entry> contextEntries = decryptedEventDto.event.context.get(0).entry;
            DecryptedEventDto.Event.Context.Entry.Resource patient = null;
            for(DecryptedEventDto.Event.Context.Entry entry: contextEntries){
                if("Patient".equals(entry.resource.resourceType)){
                    patient = entry.resource;
                    break;
                }
            }
            if (patient == null){
                throw ErrorCode.MISSING_UIN_IN_DEATH_EVENT.throwChecked();
            }
            for(DecryptedEventDto.Event.Context.Entry.Resource.Identifier identifier: patient.identifier){
                if("NATIONAL_ID".equals(OpencrvsDataUtil.getTypeCode(identifier))){
                    return identifier.value;
                }
            }
            throw ErrorCode.MISSING_UIN_IN_DEATH_EVENT.throwChecked();
        } catch(NullPointerException ne){
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX, LoggingConstants.SESSION, LoggingConstants.ID, "DeathEvent::getUIN", "Error getting UIN from death event", ne);
            throw ErrorCode.MISSING_UIN_IN_DEATH_EVENT.throwChecked(ne);
        }
    }

    public String getUINFromUINVID(String id, String token) throws BaseCheckedException{
        if(id.length()==uinLength){
            return id;
        } else if(id.length() < uinLength) {
            throw ErrorCode.UIN_NOT_VALID_IN_DEATH_EVENT.throwChecked();
        }
        try{
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Cookie", "Authorization="+token);
            HttpEntity<String> requestIdentity = new HttpEntity<>(headers);
            String apiUrl = UriComponentsBuilder.fromHttpUrl(env.getProperty(ApiName.IDREPO_VID)).pathSegment(id).toUriString();
            String response = new RestTemplate().exchange(apiUrl, HttpMethod.GET, requestIdentity, String.class).getBody();
            return new JSONObject(response).getJSONObject("response").getString("UIN");
        } catch(Exception e) {
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX, LoggingConstants.SESSION, LoggingConstants.ID, "DeathEvent::getUIN", "Error getting UIN from VID", e);
            throw ErrorCode.UIN_NOT_VALID_IN_DEATH_EVENT.throwChecked(e);
        }
    }
}
