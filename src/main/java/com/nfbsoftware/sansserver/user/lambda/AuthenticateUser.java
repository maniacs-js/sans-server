package com.nfbsoftware.sansserver.user.lambda;

import com.amazonaws.services.cognitoidentity.model.GetOpenIdTokenForDeveloperIdentityResult;
import com.nfbsoftware.sansserver.user.dao.UserDao;
import com.nfbsoftware.sansserver.user.model.AuthenticatedUser;
import com.nfbsoftware.sansserver.user.model.User;
import com.nfbsoftware.sansserverplugin.sdk.annotation.AwsLambdaWithGateway;
import com.nfbsoftware.sansserverplugin.sdk.aws.AmazonCognitoManager;
import com.nfbsoftware.sansserverplugin.sdk.lambda.BaseLambdaHandler;
import com.nfbsoftware.sansserverplugin.sdk.lambda.model.HandlerResponse;
import com.nfbsoftware.sansserverplugin.sdk.util.Entity;
import com.nfbsoftware.sansserverplugin.sdk.util.SecureUUID;
import com.nfbsoftware.sansserverplugin.sdk.util.StringUtil;

/**
 * The AuthenticateUser function is used to verify a user's username and password with that stored in the database.  If verified,
 * the function will return an AuthenticatedUser record to the client that contains identity and openID tokens for client side
 * execution of lambda functions.
 * 
 * @author Brendan Clemenzi
 */
@AwsLambdaWithGateway(
        name="AuthenticateUser", 
        desc="Cordata authentication service", 
        handlerMethod="handleRequest", 
        memorySize="512", 
        timeout="60", 
        resourceName="authenticate", 
        method=AwsLambdaWithGateway.MethodTypes.POST, 
        authorization=AwsLambdaWithGateway.AuthorizationTypes.NONE, 
        keyRequired=false, 
        enableCORS=true)
public class AuthenticateUser extends BaseLambdaHandler
{
    /**
     * 
     * @return
     * @throws Exception
     */
    @Override
    public HandlerResponse processRequest() throws Exception
    {
        HandlerResponse handlerResponse = new HandlerResponse();
        
        try
        {
            // Get the parameters for the request
            String username = StringUtil.emptyIfNull((String)this.getBody("username"));
            String clearPassword = StringUtil.emptyIfNull((String)this.getBody("password"));
            
            // Get a few runtime properties to pass back to our authenticated user
            String regionName = m_properties.getProperty(Entity.FrameworkProperties.AWS_REGION);
            String identityPoolId = m_properties.getProperty(Entity.FrameworkProperties.AWS_COGNITO_IDENTITY_POOL_ID);

            // Initialize our user datasoure
            UserDao userDao = new UserDao(this.m_properties);
            
            m_logger.log("Get user record: " + username);
            User user = userDao.getUser(username);
            
            if(user != null)
            {
                m_logger.log("Validate credentials for: " + username);
                
                // Salt and hash the password passed in to make sure it is the same as the one stored in our database
                String testHash = SecureUUID.generateSaltedMD5(clearPassword, user.getSalt());
                
                // Verify that the password hashes match and that the user is active
                if(user.getPassword().equalsIgnoreCase(testHash) && user.isActive())
                {
                    m_logger.log("Credentials validated for: " + username);
                    
                    AmazonCognitoManager amazonCognitoManager = new AmazonCognitoManager(this.m_properties);
                    
                    m_logger.log("Get OpenId Token for: " + username);
                    GetOpenIdTokenForDeveloperIdentityResult identityResult = amazonCognitoManager.getDeveloperIdentityResult(user.getUserId());
                    user.setOpenIdToken(identityResult.getToken());
                    
                    m_logger.log("Get IdentityId for: " + username);
                    String identityId = identityResult.getIdentityId();
                    user.setIdentityId(identityId);
                    
                    m_logger.log("Save the new token to our user account: " + username);
                    userDao.updateUser(user);
                    
                    // Build the response object
                    AuthenticatedUser authenticatedUser = new AuthenticatedUser();
                    authenticatedUser.setUserId(user.getUserId());
                    authenticatedUser.setFullName(user.getFullName());
                    authenticatedUser.setRegion(regionName);
                    authenticatedUser.setIdentityId(identityId);
                    authenticatedUser.setIdentityPoolId(identityPoolId);
                    authenticatedUser.setOpenIdToken(user.getOpenIdToken());
                    authenticatedUser.setApiKey(user.getApiKey());
                    
                    // Add the model to the response map
                    handlerResponse.getData().put("authenticatedUser", authenticatedUser);
                }
                else
                {
                    throw new Exception("Username or password is incorrect");
                }
            }
            else
            {
                throw new Exception("Username or password is incorrect");
            }
            
            // Set our process status
            handlerResponse.setStatus(HandlerResponse.StatusKeys.SUCCESS);
            handlerResponse.setStatusMessage("");
        }
        catch (Exception e)
        {
            m_logger.log("Error processing request: " + e.getMessage());
            
            handlerResponse.setStatus(HandlerResponse.StatusKeys.FAILURE);
            handlerResponse.setStatusMessage(e.getMessage());
        }
        
        return handlerResponse;
    }
}
