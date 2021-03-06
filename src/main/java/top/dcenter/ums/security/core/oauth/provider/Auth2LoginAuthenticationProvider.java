/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.dcenter.ums.security.core.oauth.provider;

import lombok.extern.slf4j.Slf4j;
import me.zhyd.oauth.model.AuthUser;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserCache;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.core.userdetails.cache.NullUserCache;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import top.dcenter.ums.security.core.oauth.entity.ConnectionData;
import top.dcenter.ums.security.core.oauth.justauth.request.Auth2DefaultRequest;
import top.dcenter.ums.security.core.oauth.repository.UsersConnectionRepository;
import top.dcenter.ums.security.core.oauth.service.Auth2UserService;
import top.dcenter.ums.security.core.oauth.service.UmsUserDetailsService;
import top.dcenter.ums.security.core.oauth.signup.ConnectionService;
import top.dcenter.ums.security.core.oauth.token.Auth2AuthenticationToken;
import top.dcenter.ums.security.core.oauth.token.Auth2LoginAuthenticationToken;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static top.dcenter.ums.security.core.oauth.util.MvcUtil.toJsonString;

/**
 * An implementation of an {@link AuthenticationProvider} for OAuth 2.0 Login, which
 * leverages the OAuth 2.0 Authorization Code Grant Flow.
 *
 * This {@link AuthenticationProvider} is responsible for authenticating an Authorization
 * Code credential with the Authorization Server's Token Endpoint and if valid, exchanging
 * it for an Access Token credential.
 * <p>
 * It will also obtain the user attributes of the End-User (Resource Owner) from the
 * UserInfo Endpoint using an {@link Auth2UserService}, which will create a
 * {@code Principal} in the form of an {@link AuthUser}. The {@code AuthUser} is then
 * associated to the {@link Auth2LoginAuthenticationToken} to complete the
 * authentication.
 *
 * @author Joe Grandja
 * @author YongWu zheng
 * @since 5.0
 * @see UsersConnectionRepository
 * @see Auth2AuthenticationToken
 * @see Auth2UserService
 * @see &#60;a target="_blank" href="https://tools.ietf.org/html/rfc6749#section-4.1"&#62;Section
 * 4.1 Authorization Code Grant Flow&#60;/a&#62;
 * @see &#60;a target="_blank" href=
 * "https://tools.ietf.org/html/rfc6749#section-4.1.3"&#62;Section 4.1.3 Access Token
 * Request&#60;/a&#62;
 * @see &#60;a target="_blank" href=
 * "https://tools.ietf.org/html/rfc6749#section-4.1.4"&#62;Section 4.1.4 Access Token
 * Response&#60;/a&#62;
 */
@SuppressWarnings({"JavaDoc", "unused"})
@Slf4j
public class Auth2LoginAuthenticationProvider implements AuthenticationProvider {

	private final Auth2UserService userService;
	private final UmsUserDetailsService umsUserDetailsService;
	private final UsersConnectionRepository usersConnectionRepository;
	private final ConnectionService connectionService;
	private final ExecutorService updateConnectionTaskExecutor;

	protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();
	private UserCache userCache = new NullUserCache();
	private UserDetailsChecker preAuthenticationChecks = new DefaultPreAuthenticationChecks();
	private UserDetailsChecker postAuthenticationChecks = new DefaultPostAuthenticationChecks();

	/**
	 * Constructs an {@code Auth2LoginAuthenticationProvider} using the provided
	 * parameters.
	 * @param userService the service used for obtaining the user attributes of the
	 * End-User from the UserInfo Endpoint
	 * @param connectionService  第三方登录成功后自动注册服务
	 * @param umsUserDetailsService this service used for local user service
	 * @param usersConnectionRepository    users connection repository
	 * @param updateConnectionTaskExecutor
	 */
	public Auth2LoginAuthenticationProvider(Auth2UserService userService,
	                                        ConnectionService connectionService,
	                                        UmsUserDetailsService umsUserDetailsService,
	                                        UsersConnectionRepository usersConnectionRepository,
	                                        ExecutorService updateConnectionTaskExecutor) {
		Assert.notNull(updateConnectionTaskExecutor, "updateConnectionTaskExecutor cannot be null");
		Assert.notNull(userService, "userService cannot be null");
		Assert.notNull(connectionService, "connectionService cannot be null");
		Assert.notNull(umsUserDetailsService, "umsUserDetailsService cannot be null");
		Assert.notNull(usersConnectionRepository, "usersConnectionRepository cannot be null");
		this.updateConnectionTaskExecutor = updateConnectionTaskExecutor;
		this.connectionService = connectionService;
		this.userService = userService;
		this.umsUserDetailsService = umsUserDetailsService;
		this.usersConnectionRepository = usersConnectionRepository;
	}

	@SuppressWarnings("AlibabaMethodTooLong")
	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		Auth2LoginAuthenticationToken loginToken = (Auth2LoginAuthenticationToken) authentication;
		Auth2DefaultRequest auth2DefaultRequest = loginToken.getAuth2DefaultRequest();

		//1 从第三方获取 Userinfo
		HttpServletRequest request = loginToken.getRequest();
		AuthUser authUser = userService.loadUser(auth2DefaultRequest, request);

		//2 检测是否已经有第三方的授权记录, List 按 rank 排序, 直接取第一条记录
		String providerUserId = authUser.getUuid();
		List<ConnectionData> connectionDataList = usersConnectionRepository
				.findConnectionByProviderIdAndProviderUserId(auth2DefaultRequest.getProviderId(), providerUserId);

		//3 获取 securityContext 中的 authenticationToken, 判断是否为授权用户(不含匿名用户)
		final Authentication authenticationToken = SecurityContextHolder.getContext().getAuthentication();
		Object principal = null;
		if (authenticationToken != null && authenticationToken.isAuthenticated()
				&& !(authenticationToken instanceof AnonymousAuthenticationToken))
		{
			principal = authenticationToken.getPrincipal();
		}

		boolean cacheWasUsed = false;
		UserDetails userDetails = null;
		//4.1 没有第三方登录记录, 自动注册 或 绑定
		if (CollectionUtils.isEmpty(connectionDataList))
		{
			if (principal == null)
			{
				// 注册到本地账户, 注册第三方授权登录信息到 user_connection 与 auth_token
				userDetails = connectionService.signUp(authUser, auth2DefaultRequest.getProviderId());
			}
			// 绑定
			else
			{
				if (principal instanceof UserDetails)
				{
					// 当 principal 为 UserDetails 类型是进行绑定操作.
					connectionService.binding((UserDetails) principal, authUser, auth2DefaultRequest.getProviderId());
				}
			}
		}
		//4.2 有第三方登录记录
		else
		{
			ConnectionData connectionData = null;
			// SecurityContextHolder 中有已认证用户
			if (principal instanceof UserDetails)
			{
				userDetails = (UserDetails) principal;
				// 已认证用户 userId
				final String userId = userDetails.getUsername();
				for (ConnectionData data : connectionDataList)
				{
					if (userId.equals(data.getUserId()))
					{
						// 与认证的 userId 相同, 跳过第三方授权登录流程
						connectionData = data;
						break;
					}
				}

				// 与已认证的 userId 不同
				if (connectionData == null)
				{
					// 走第三方授权登录流程
					userDetails = null;
					principal = null;
				}
			}

			// 第三方授权登录流程
			if (userDetails == null)
			{
				// 扩展点, 待实现让用户选择哪一个本地账户登录, 这里直接取第一条记录.
				connectionData = connectionDataList.get(0);
				final String userId = connectionData.getUserId();
				userDetails = this.userCache.getUserFromCache(userId);
				cacheWasUsed = true;
				if (userDetails == null) {
					cacheWasUsed = false;
					userDetails = umsUserDetailsService.loadUserByUserId(userId);
				}
			}

			// 异步更新第三方授权登录用户信息与 token 信息, 异步更新执行失败再次进行同步更新.
			asyncUpdateUserConnectionAndToken(authUser, connectionData);
		}

		// 5 删除 session 中的 state 缓存
		Auth2DefaultRequest.removeStateCacheOfSessionCache(auth2DefaultRequest.getAuthStateCache(),
		                                                   auth2DefaultRequest.getAuthSource());

		// 6 已认证用户, 直接返回
		if (principal != null)
		{
			return authenticationToken;
		}

		// 认证成功后前置与后置检查
		try {
			preAuthenticationChecks.check(userDetails);
			additionalAuthenticationChecks(userDetails, (Auth2LoginAuthenticationToken) authentication);
		}
		catch (AuthenticationException exception) {
			if (cacheWasUsed) {
				// There was a problem, so try again after checking
				// we're using latest data (i.e. not from the cache)
				cacheWasUsed = false;
				userDetails = umsUserDetailsService.loadUserByUserId(userDetails.getUsername());
				preAuthenticationChecks.check(userDetails);
				additionalAuthenticationChecks(userDetails, (Auth2LoginAuthenticationToken) authentication);
			}
			else {
				throw exception;
			}
		}

		postAuthenticationChecks.check(userDetails);

		// 放入缓存
		if (!cacheWasUsed) {
			this.userCache.putUserInCache(userDetails);
		}

		// 7 创建成功认证 token 并返回
		Auth2AuthenticationToken auth2AuthenticationToken = new Auth2AuthenticationToken(userDetails, userDetails.getAuthorities(),
		                                                                                 auth2DefaultRequest.getProviderId());
		auth2AuthenticationToken.setDetails(loginToken.getDetails());

		return auth2AuthenticationToken;
	}

	/**
	 * 异步更新第三方授权登录用户信息与 token 信息, 异步更新执行失败再次进行同步更新.
	 * @param authUser          {@link AuthUser}
	 * @param connectionData    {@link ConnectionData}
	 */
	private void asyncUpdateUserConnectionAndToken(AuthUser authUser, ConnectionData connectionData) {
		try {
			// 异步更新第三方授权登录用户信息与 token 信息, 拒绝策略为: CALLER_RUNS
			updateConnectionTaskExecutor.execute(
					() -> {
						try {
							connectionService.updateUserConnection(authUser, connectionData);
						}
						catch (Exception e) {
							String msg = String.format("异步更新第三方授权登录用户信息与 token 信息失败: AuthUser=%s, ConnectionData=%s, error=%s",
							                    toJsonString(authUser),
							                    toJsonString(connectionData),
							                    e.getMessage());
							log.error(msg, e);
						}
					});
		}
		catch (RejectedExecutionException | NullPointerException e) {
			log.error(String.format("异步更新第三方授权登录用户信息与 token 信息失败: %s, 再次同步更新", e.getMessage()), e);
			// 异步执行失败, 直接同步更新授权登录用户信息与 token 信息
			try {
				connectionService.updateUserConnection(authUser, connectionData);
			}
			catch (Exception ex) {
				String msg = String.format("同步更新第三方授权登录用户信息与 token 信息失败: AuthUser=%s, ConnectionData=%s, error=%s",
					                           toJsonString(authUser),
					                           toJsonString(connectionData),
					                           e.getMessage());
				log.error(msg, e);
			}
		}
	}

	/**
	 * Allows subclasses to perform any additional checks of a returned (or cached)
	 * <code>UserDetails</code> for a given authentication request. If custom logic is needed to compare additional
	 * properties of <code>UserDetails</code> and/or
	 * <code>Auth2LoginAuthenticationToken</code>, these should also appear in this
	 * method.
	 *
	 * @param userDetails as retrieved from the
	 * {@link ConnectionService#signUp(AuthUser, String)}} or
	 * <code>UserCache</code> or {@link UmsUserDetailsService#loadUserByUserId(String)}
	 * @param authentication the current request that needs to be authenticated
	 *
	 * @throws AuthenticationException AuthenticationException if the userDetails could
	 * not be validated (generally an <code>AuthenticationServiceException</code>)
	 */
	@SuppressWarnings("unused")
	protected void additionalAuthenticationChecks(UserDetails userDetails,
	                                              Auth2LoginAuthenticationToken authentication) throws AuthenticationException {
		// 第三方授权登录, 不需要对密码校验.
	}

	public void setUserCache(UserCache userCache) {
		this.userCache = userCache;
	}

	protected UserDetailsChecker getPreAuthenticationChecks() {
		return preAuthenticationChecks;
	}

	/**
	 * Sets the policy will be used to verify the status of the loaded
	 * <tt>UserDetails</tt> <em>before</em> validation of the credentials takes place.
	 *
	 * @param preAuthenticationChecks strategy to be invoked prior to authentication.
	 */
	public void setPreAuthenticationChecks(UserDetailsChecker preAuthenticationChecks) {
		this.preAuthenticationChecks = preAuthenticationChecks;
	}

	protected UserDetailsChecker getPostAuthenticationChecks() {
		return postAuthenticationChecks;
	}

	public void setPostAuthenticationChecks(UserDetailsChecker postAuthenticationChecks) {
		this.postAuthenticationChecks = postAuthenticationChecks;
	}

	private class DefaultPreAuthenticationChecks implements UserDetailsChecker {
		@Override
		public void check(UserDetails user) {
			if (!user.isAccountNonLocked()) {
				log.debug("User account is locked");

				throw new LockedException(messages.getMessage(
						"AbstractUserDetailsAuthenticationProvider.locked",
						"User account is locked"));
			}

			if (!user.isEnabled()) {
				log.debug("User account is disabled");

				throw new DisabledException(messages.getMessage(
						"AbstractUserDetailsAuthenticationProvider.disabled",
						"User is disabled"));
			}

			if (!user.isAccountNonExpired()) {
				log.debug("User account is expired");

				throw new AccountExpiredException(messages.getMessage(
						"AbstractUserDetailsAuthenticationProvider.expired",
						"User account has expired"));
			}
		}
	}

	private class DefaultPostAuthenticationChecks implements UserDetailsChecker {
		@Override
		public void check(UserDetails user) {
			if (!user.isCredentialsNonExpired()) {
				log.debug("User account credentials have expired");

				throw new CredentialsExpiredException(messages.getMessage(
						"AbstractUserDetailsAuthenticationProvider.credentialsExpired",
						"User credentials have expired"));
			}
		}
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return Auth2LoginAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
