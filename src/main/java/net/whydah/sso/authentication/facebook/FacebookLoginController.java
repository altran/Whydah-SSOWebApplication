package net.whydah.sso.authentication.facebook;

import com.restfb.types.User;
import net.whydah.sso.authentication.ModelHelper;
import net.whydah.sso.authentication.UserCredential;
import net.whydah.sso.authentication.whydah.CookieManager;
import net.whydah.sso.authentication.whydah.SSOLoginController;
import net.whydah.sso.config.AppConfig;
import net.whydah.sso.usertoken.TokenServiceClient;
import net.whydah.sso.usertoken.UserTokenXpathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * @author <a href="mailto:erik.drolshammer@altran.com">Erik Drolshammer</a>
 * @since 26/09/12
 */
@Controller
public class FacebookLoginController {
    private static final Logger log = LoggerFactory.getLogger(FacebookLoginController.class);
    private final TokenServiceClient tokenServiceClient = new TokenServiceClient();

    // set this to your servlet URL for the authentication servlet/filter
    private final String fbauthURI;

    public FacebookLoginController() throws IOException {
        Properties properties = AppConfig.readProperties();
        String MY_APP_URI = properties.getProperty("myuri");
        fbauthURI = MY_APP_URI + "fbauth";
    }


    @RequestMapping("/fblogin")
    public String facebookLogin(HttpServletRequest request, Model model) throws MalformedURLException {
        String clientRedirectURI = request.getParameter("redirectURI");
        String facebookLoginUrl = FacebookHelper.getFacebookLoginUrl(clientRedirectURI, fbauthURI);
        String LOGOURL="/sso/images/site-logo.png";
        try {
            Properties properties = AppConfig.readProperties();
            LOGOURL = properties.getProperty("logourl");

        } catch (Exception e){

        }
        model.addAttribute("logoURL", LOGOURL);

        model.addAttribute("redirect", facebookLoginUrl);
        log.info("Redirecting to {}", facebookLoginUrl);
        return "action";
    }

    @RequestMapping("/fbauth")
    public String facebookAuth(HttpServletRequest request, HttpServletResponse response, Model model) throws MalformedURLException {
        String code = request.getParameter("code");

        log.trace("fbauth got code: {}",code);
        log.trace("fbauth - got state: {}",request.getParameter("state"));
        Map.Entry<String, User> pair = FacebookHelper.loginAndCreateFacebookUser(code, fbauthURI);
        if (pair == null) {
            log.error("Could not fetch facebok user.");
            //TODO Do we need to add client redirect URI here?
            ModelHelper.setEnabledLoginTypes(model);
            return "login";
        }
        String fbAccessToken = pair.getKey();
        User fbUser = pair.getValue();

        String LOGOURL="/sso/images/site-logo.png";
        try {
            Properties properties = AppConfig.readProperties();
            LOGOURL = properties.getProperty("logourl");
        } catch (Exception e){

        }
        model.addAttribute("logoURL", LOGOURL);
        ModelHelper.setEnabledLoginTypes(model);


        String userticket = UUID.randomUUID().toString();
        UserCredential userCredential;
        try {
            String username = fbUser.getUsername();
            // Since Facebook sometimes returns username=null, we fallback on using facebook user email as username
            if (username==null || username.length()<6){
                if (fbUser.getEmail()==null || fbUser.getEmail().length()<6){
                    username=fbUser.getId();
                    log.trace("facebook returned username, email=null, using facebook id as username instead");

                } else {
                    username=fbUser.getEmail();
                    log.trace("facebook returned username=null, using facebook email as username instead");

                }
            }
            log.trace("new FacebookUserCredential(fbUser.getId({}),  getUsername({})",fbUser.getId(), username);
            userCredential = new FacebookUserCredential(fbUser.getId(), username);
        } catch(IllegalArgumentException iae) {
            log.error("fbauth - unable to build usercredential for facebook token.",iae.getLocalizedMessage());
            //TODO Do we need to add client redirect URI here?
            return "login";
        }


        //Check om fbToken har session i lokal cache i TokenService
        // Hvis ja, hent whydah user usertoken og legg ticket på model eller på returURL.
        String userTokenXml = tokenServiceClient.getUserToken(userCredential, userticket);
        if (userTokenXml == null) {
            log.warn("getUserToken failed. Try to create new user using facebook credentials.");
            // Hvis nei, hent brukerinfo fra FB, kall tokenService. med user credentials for ny bruker (lag tjenesten i TokenService).
            // Success etter ny bruker er laget = usertoken. Alltid ticket id som skal sendes.


            userTokenXml = tokenServiceClient.createAndLogonUser(fbUser, fbAccessToken, userCredential, userticket);
            if (userTokenXml == null) {
                log.error("createAndLogonUser failed. Did not get a valid UserToken. Redirecting to login page.");
                String redirectURI = getRedirectURI(request);
                model.addAttribute("redirectURI", redirectURI);
                model.addAttribute("loginError", "Login error: Could not create or authenticate user.");
                ModelHelper.setEnabledLoginTypes(model);
                return "login";
            }
        }


        String userTokenId = UserTokenXpathHelper.getUserTokenId(userTokenXml);
        Integer tokenRemainingLifetimeSeconds = TokenServiceClient.calculateTokenRemainingLifetimeInSeconds(userTokenXml);
        CookieManager.createAndSetUserTokenCookie(userTokenId, tokenRemainingLifetimeSeconds, response);

        String clientRedirectURI = request.getParameter("state");
        clientRedirectURI = tokenServiceClient.appendTicketToRedirectURI(clientRedirectURI, userticket);
        log.info("Redirecting to {}", clientRedirectURI);
        model.addAttribute("redirect", clientRedirectURI);
        return "action";
    }

    private String getRedirectURI(HttpServletRequest request) {
        String redirectURI = request.getParameter("fbauthURI");
        log.debug("fbauthURI from request: {}", redirectURI);
        if (redirectURI == null || redirectURI.length() < 4) {
            log.warn("No fbauthURI found, setting to {}", SSOLoginController.DEFAULT_REDIRECT);
            return SSOLoginController.DEFAULT_REDIRECT;
        }
        return redirectURI;
    }
}