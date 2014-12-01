package net.whydah.sso.notinuse.commands;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import net.whydah.sso.config.AppConfig;
import net.whydah.sso.usertoken.ApplicationCredential;
import net.whydah.sso.usertoken.UserTokenXpathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;

/**
 * Created by totto on 12/1/14.
 */
public class CommandLogonApplication extends HystrixCommand<String> {

    private static final Logger logger = LoggerFactory.getLogger(CommandLogonApplication.class);



    private final String name;

    public CommandLogonApplication(String name) {
        super(HystrixCommandGroupKey.Factory.asKey("SSOAuthGroup"));
        this.name = name;
    }

    @Override
    protected String run() {

        Properties properties=null;
        try {
            properties = AppConfig.readProperties();
        } catch (IOException ioe){
            logger.warn("CommandLogonApplication - unable to read configuration.",ioe);
        }
        URI tokenServiceUri = UriBuilder.fromUri(properties.getProperty("securitytokenservice")).build();
        String applicationid = properties.getProperty("applicationid");
        String applicationsecret= properties.getProperty("applicationsecret");

        Client tokenServiceClient = Client.create();
        WebResource logonResource = tokenServiceClient.resource(tokenServiceUri).path("logon");
        MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
        ApplicationCredential appCredential = new ApplicationCredential();
        appCredential.setApplicationID(applicationid);
        appCredential.setApplicationPassord(applicationsecret);


        formData.add("applicationcredential", appCredential.toXML());
        ClientResponse response;
        try {
            response = logonResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
        } catch (RuntimeException e) {
            logger.error("logonApplication - Problem connecting to {}", logonResource.toString());
            throw(e);
        }
        //todo håndtere feil i statuskode + feil ved app-pålogging (retry etc)
        if (response.getStatus() != 200) {
            logger.error("Application authentication failed with statuscode {}", response.getStatus());
            throw new RuntimeException("Application authentication failed");
        }
        String myAppTokenXml = response.getEntity(String.class);
        String myAppTokenId = UserTokenXpathHelper.getAppTokenIdFromAppToken(myAppTokenXml);
        logger.debug("Applogon ok: apptokenxml: {}", myAppTokenXml);
        logger.debug("myAppTokenId: {}", myAppTokenId);
        return myAppTokenId;
    }
}