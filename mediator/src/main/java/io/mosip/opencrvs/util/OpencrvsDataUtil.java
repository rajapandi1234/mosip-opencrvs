package io.mosip.opencrvs.util;

import io.mosip.kernel.core.exception.BaseCheckedException;
import io.mosip.kernel.core.exception.BaseUncheckedException;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.StringUtils;
import io.mosip.opencrvs.constant.LoggingConstants;
import io.mosip.opencrvs.dto.DecryptedEventDto;
import io.mosip.opencrvs.dto.ReceiveDto;
import io.mosip.opencrvs.error.ErrorCode;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

@Component
public class OpencrvsDataUtil {
    private static Logger LOGGER = LogUtil.getLogger(OpencrvsDataUtil.class);

    @Value("${IDSchema.Version}")
    private String idschemaVersion;
    @Value("${opencrvs.data.lang.code.default}")
    private String defaultLangCode;
    @Value("${opencrvs.locations.url}")
    private String locationsUrl;
    @Value("${opencrvs.data.lang.code.mapping}")
    private String langCodeMapping;
    @Value("${opencrvs.data.address.line.mapping}")
    private String addressLineMapping;
    @Value("${opencrvs.data.address.location.mapping}")
    private String addressLocationMapping;
    @Value("${opencrvs.data.address.line.joiner}")
    private String addressLineJoiner;
    @Value("${opencrvs.data.dummy.phone}")
    private String dummyPhone;
    @Value("${opencrvs.data.dummy.emailSuffix}")
    private String dummyEmailSuffix;

    @Autowired
    private Environment env;

    @Autowired
    private RestTokenUtil restTokenUtil;

    // private JSONObject opencrvsLocationsJson;

    // @PostConstruct
    // public void initLocations(){
    //     opencrvsLocationsJson = fetchAllAddresses();
    // }

    public ReceiveDto buildIdJson(DecryptedEventDto opencrvsRequestBody){
        List<DecryptedEventDto.Event.Context.Entry> contextEntries;
        DecryptedEventDto.Event.Context.Entry.Resource child = null;
        DecryptedEventDto.Event.Context.Entry.Resource mother = null;
        DecryptedEventDto.Event.Context.Entry.Resource task = null;
        try{
            contextEntries = opencrvsRequestBody.event.context.get(0).entry;
            for(DecryptedEventDto.Event.Context.Entry entry: contextEntries){
                if("Task".equals(entry.resource.resourceType)) {
                    task = entry.resource;
                } else if(child==null && "Patient".equals(entry.resource.resourceType)){
                    child = entry.resource;
                } else if(child!=null && "Patient".equals(entry.resource.resourceType)){
                    mother = entry.resource;
                }
                if (child!=null && mother!=null && task!=null) break;
            }
            if(child == null || mother == null || task == null ){
                LOGGER.error(LoggingConstants.SESSION,LoggingConstants.ID,"ReceiveDto::build()","Error processing child or mother or task. Got null child or mother or task");
                throw ErrorCode.JSON_PROCESSING_EXCEPTION.throwUnchecked();
            }
        } catch(NullPointerException ne){
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX,LoggingConstants.SESSION,LoggingConstants.ID,"ReceiveDto::build()", "Received null pointer exception", ne);
            throw ErrorCode.JSON_PROCESSING_EXCEPTION.throwUnchecked(ne);
        }

        ReceiveDto returner = new ReceiveDto();

        returner.setOpencrvsBRN(getOpencrvsBRNFromPatientBody(child));

        String fullName = getFullNameFromPatientBody(child);
        Map<String, Object> primaryAddress = getPrimaryAddressFromPatient(mother);

        returner.setIdentityJson("{"+
            "\"introducerBiometrics\":\"null\"," +
            "\"identity\":{" +
                "\"IDSchemaVersion\":" + idschemaVersion + "," +
                "\"fullName\":" + fullName + "," +
                "\"dateOfBirth\":" + getDOBFromPatientBody(child) + "," +
                "\"gender\":" + getGenderFromPatientBody(child) +"," +
                getAddressLinesFromAddress(primaryAddress, addressLineMapping, addressLineJoiner, defaultLangCode) +
                getAddressLocationFromAddress(primaryAddress, addressLocationMapping, defaultLangCode) +
                //"\"phone\":" + getPhoneFromTaskBody(task) + "," +
                "\"phone\":" + dummyPhone + "," +
                "\"email\":" + getEmailFromPatientBody(child) + "," +
                "\"proofOfIdentity\":" +
                "null" +
                //"{" +
                //    "\"refNumber\":null," +
                //    "\"format\":\"pdf\"," +
                //    "\"type\":\"Reference Identity Card\"," +
                //    "\"value\":\"Some_cert\"" +
                //"}" +
                "," +
                "\"individualBiometrics\":" +
                "null" +
                //"{" +
                //    "\"format\":\"cbeff\"," +
                //    "\"version\":1," +
                //    "\"value\":\"individualBiometrics_bio_CBEFF\"" +
                //"}" +
            "}" +
        "}");

        return returner;
    }

    public String getOpencrvsBRNFromPatientBody(DecryptedEventDto.Event.Context.Entry.Resource patient){
        for(DecryptedEventDto.Event.Context.Entry.Resource.Identifier identifier : patient.identifier){
            if("BIRTH_REGISTRATION_NUMBER".equals(identifier.type)){
                return identifier.value;
            }
        }
        return null;
    }

    public String getFullNameFromPatientBody(DecryptedEventDto.Event.Context.Entry.Resource patient){
        try{
            String ret = "";
            List<DecryptedEventDto.Event.Context.Entry.Resource.Name> names = patient.name;

            ret+="[";
            for (int i=0;i<names.size();i++){
                String givenName = String.join(" ", names.get(i).given).replaceAll("\"","");
                String familyName = String.join(" ", names.get(i).family).replaceAll("\"","");
                String langCode = names.get(i).use;

                boolean isSet = false;
                for(String langMap: langCodeMapping.split(",")){
                    for (String str : langMap.split(":")[1].split("\\|")){
                        if (str.equalsIgnoreCase(langCode)){
                            isSet=true;
                            langCode=langMap.split(":")[0];
                            break;
                        }
                    }
                    if(isSet) break;
                }
                if(!isSet) langCode = "eng";

                ret += returnValueWithLangCode(givenName + " " + familyName, langCode);
                if(i!=names.size()-1) ret+=",";
            }
            ret+="]";
            return ret;
        } catch(NullPointerException ne){
            throw ErrorCode.JSON_PROCESSING_EXCEPTION.throwUnchecked("while getting Full Name from request ", ne);
        }
    }

    public String getGenderFromPatientBody(DecryptedEventDto.Event.Context.Entry.Resource patient){
        try{
            return returnSingleValueInArrayWithLangCode(StringUtils.capitalizeFirstLetter(patient.gender), defaultLangCode);
        } catch(NullPointerException ne){
            throw ErrorCode.JSON_PROCESSING_EXCEPTION.throwUnchecked("while getting gender from request ", ne);
        }
    }

    public String getEmailFromPatientBody(DecryptedEventDto.Event.Context.Entry.Resource patient){
        //dummy implementation
        String staticEmail = env.getProperty("opencrvs.data.dummy.static.email");
        if(staticEmail!=null && !staticEmail.isEmpty()){
            return "\"" + staticEmail + "\"";
        }
        try{
            DecryptedEventDto.Event.Context.Entry.Resource.Name defaultName = patient.name.get(0);
            String givenName = String.join(".", defaultName.given).replaceAll("\\s","").replaceAll("\"","").toLowerCase();
            String familyName = String.join(".", defaultName.family).replaceAll("\\s","").replaceAll("\"","").toLowerCase();
            return "\"" + givenName + "." + familyName + dummyEmailSuffix.replaceAll("\"","") + "\"";
        } catch(NullPointerException ne){
            throw ErrorCode.JSON_PROCESSING_EXCEPTION.throwUnchecked("while getting email id from request ", ne);
        }
    }

    public String getDOBFromPatientBody(DecryptedEventDto.Event.Context.Entry.Resource patient){
        try{
            return "\""+patient.birthDate.replaceAll("-","\\/")+"\"";
        } catch(NullPointerException ne){
            throw ErrorCode.JSON_PROCESSING_EXCEPTION.throwUnchecked("while getting DOB from request", ne);
        }
    }

    public String getPhoneFromTaskBody(DecryptedEventDto.Event.Context.Entry.Resource task){
        try{
            for(DecryptedEventDto.Event.Context.Entry.Resource.Extension extension : task.extension){
                if (extension.url.toLowerCase().endsWith("contact-person-phone-number")){
                    return "\"" + extension.valueString + "\"";
                }
            }
            return null;
        } catch(NullPointerException ne){
            throw ErrorCode.JSON_PROCESSING_EXCEPTION.throwUnchecked("while getting phone number from request ", ne);
        }
    }

    public String getRidFromBody(DecryptedEventDto decryptedEvent){
        List<DecryptedEventDto.Event.Context.Entry> contextEntries;
        try{
            contextEntries = decryptedEvent.event.context.get(0).entry;
            for(DecryptedEventDto.Event.Context.Entry entry: contextEntries) {
                if ("Patient".equals(entry.resource.resourceType)) {
                    for(DecryptedEventDto.Event.Context.Entry.Resource.Identifier identifier : entry.resource.identifier){
                        if("MOSIP_AID".equals(identifier.type)){
                            return identifier.value;
                        }
                    }
                    break;
                }
            }
            return null;
        } catch(NullPointerException e) {
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX,LoggingConstants.SESSION,LoggingConstants.ID,"ReceiveDto::build()","Error processing child or mother or task. Got null child or mother or task", e);
            return null;
        }
    }

    public Map<String, Object> getPrimaryAddressFromPatient(DecryptedEventDto.Event.Context.Entry.Resource patient){
        for(Map<String, Object> address: patient.address){
            if("PRIMARY_ADDRESS".equals(address.get("type"))){
                return address;
            }
        }
        return null;
    }

    public String returnSingleValueInArrayWithLangCode(String value, String langCode){
        return "[" + returnValueWithLangCode(value, langCode) + "]";
    }
    public String returnValueWithLangCode(String value, String langCode){
        return "{\"language\":\"" + langCode + "\",\"value\":\"" + value + "\"}";
    }

    public String getAddressLinesFromAddress(Map<String, Object> address, String mapping, String joiner, String langCode){
        String toReturn = "";
        for (String mappingLine : mapping.split("\\|")) {
            String mosipLineNumber = mappingLine.split(":")[0];
            String opencrvsLines = mappingLine.split(":")[1];
            int opencrvsStartingLineNumber = Integer.parseInt(opencrvsLines.split("-")[0]) - 1;
            int opencrvsEndingLineNumber = Integer.parseInt(opencrvsLines.split("-")[1]) - 1;
            String lineValue = "";
            for(int i=opencrvsStartingLineNumber;i<opencrvsEndingLineNumber; i++){
                if (i!=opencrvsStartingLineNumber) lineValue += joiner.replaceAll("\"", "");
                lineValue += ((List<String>) address.get("line")).get(i);
            }
            toReturn += "\"addressLine" + mosipLineNumber + "\": " + returnSingleValueInArrayWithLangCode(lineValue, langCode) + ",";
        }
        return toReturn;
    }

    public String getAddressLocationFromAddress(Map<String, Object> address, String mapping, String langCode){
        String toReturn = "";
        for (String mappingLocation : mapping.split("\\|")) {
            String mosipLocation = mappingLocation.split(":")[0];
            String opencrvsLocation = mappingLocation.split(":")[1];
            String opencrvsLocationIfId = mappingLocation.split(":")[2];
            if("id".equals(opencrvsLocationIfId)){
                toReturn += "\"" + mosipLocation + "\": " + returnSingleValueInArrayWithLangCode(fetchAddressValueFromId((String)address.get(opencrvsLocation)), langCode) + ",";
            } else {
                toReturn += "\"" + mosipLocation + "\": " + returnSingleValueInArrayWithLangCode((String)address.get(opencrvsLocation), langCode) + ",";
            }
        }
        return toReturn;
    }

    public String fetchAddressValueFromId(String id){
        try{
            String url = locationsUrl + "/" + id;
            LOGGER.info("Calling location url endpoint - " + url);
            JSONObject json = new JSONObject(
                new RestTemplate().getForObject(url, String.class));
            return json.getString("name");
        } catch(Exception e){
            throw ErrorCode.ADDRESS_FETCHING_EXCEPTION.throwUnchecked(e);
        }
    }

    // public JSONObject fetchAllAddresses(){
    //     String token = restTokenUtil.getOpencrvsAuthToken("Fetching addresses.");
    //     if(token==null || token.isEmpty()){
    //         throw ErrorCode.ADDRESS_FETCHING_EXCEPTION.throwUnchecked();
    //     }
    //     HttpHeaders headers = new HttpHeaders();
    //     headers.set("Authorization", "Bearer " + token);
    //     try{
    //         return new JSONObject(new RestTemplate().exchange(locationsUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody());
    //     } catch (Exception e){
    //         throw ErrorCode.ADDRESS_FETCHING_EXCEPTION.throwUnchecked(e);
    //     }
    // }
}
