package com.redv.huobi;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redv.huobi.domain.Delegation;
import com.redv.huobi.domain.Depth;
import com.redv.huobi.domain.Funds;
import com.redv.huobi.domain.LoginResult;
import com.redv.huobi.domain.MyTradeInfo;
import com.redv.huobi.domain.TradeResult;
import com.redv.huobi.domain.Type;
import com.redv.huobi.valuereader.DelegationReader;
import com.redv.huobi.valuereader.JsonValueReader;
import com.redv.huobi.valuereader.LoginResultReader;
import com.redv.huobi.valuereader.VoidValueReader;

public class HUOBIClient implements AutoCloseable {

	public static final String ENCODING = "UTF-8";

	private static final URI HTTPS_BASE = URI.create("https://www.huobi.com/");

	private static final URI LOGIN_URI = URIUtils.resolve(HTTPS_BASE, "account/login.php");

	private static final URI DEPTH_URI = URI.create("http://market.huobi.com/market/depth.php");

	private static final URI TRADE_URI = URIUtils.resolve(HTTPS_BASE, "trade/index.php");

	private static final URI ACCOUNT_AJAX_URI = URIUtils.resolve(HTTPS_BASE, "account/ajax.php");

	private static final URI CANCEL_REFERER_URI = URIUtils.resolve(TRADE_URI, "?a=delegation");

	private final Logger log = LoggerFactory.getLogger(HUOBIClient.class);

	private final HttpClient httpClient;

	private final String email;

	private final String password;

	public HUOBIClient(
			int socketTimeout,
			int connectTimeout,
			int connectionRequestTimeout) {
		this(null, null, socketTimeout, connectTimeout, connectionRequestTimeout);
	}

	public HUOBIClient(
			String email,
			String password,
			int socketTimeout,
			int connectTimeout,
			int connectionRequestTimeout) {
		httpClient = new HttpClient(socketTimeout, connectTimeout, connectionRequestTimeout);
		this.email = email;
		this.password = password;
	}

	public void login() throws IOException {
		initLoginPage();

		LoginResult loginResult = httpClient.post(
				LOGIN_URI,
				new LoginResultReader(),
				new BasicNameValuePair("email", email),
				new BasicNameValuePair("password", password));
		log.debug("Login result: {}", loginResult);
	}

	public Depth getDepth() throws IOException {
		final URI depthUri;
		try {
			depthUri = new URIBuilder(DEPTH_URI)
				.setParameter("a", "marketdepth")
				.setParameter("random", String.valueOf(Math.random()))
				.build();
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}

		return httpClient.get(depthUri, Depth.class);
	}

	public Funds getFunds() throws IOException {
		LoginResult loginResult = httpClient.get(HTTPS_BASE,
				new LoginResultReader());
		return loginResult.getFunds();
	}

	public MyTradeInfo getMyTradeInfo() throws IOException {
		URI uri;
		try {
			uri = new URIBuilder(ACCOUNT_AJAX_URI)
				.setParameter("m", "my_trade_info")
				.setParameter("r", String.valueOf(Math.random()))
				.build();
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
		return httpClient.get(uri, MyTradeInfo.class);
	}

	public BigDecimal getMinAmountPerOrder() {
		return new BigDecimal("0.001");
	}

	public void buy(BigDecimal price, BigDecimal amount) throws IOException {
		trade(Type.BUY, price, amount);
	}

	public void sell(BigDecimal price, BigDecimal amount) throws IOException {
		trade(Type.SELL, price, amount);
	}

	/**
	 * Cancels the delegation with the given ID and returns the left delegations.
	 *
	 * @param id the ID of the delegation to cancel.
	 * @return the left delegations.
	 * @throws IOException indicates I/O exception.
	 * @deprecated the return type will be changed to void
	 */
	public List<Delegation> cancel(long id) throws IOException {
		List<NameValuePair> params = new ArrayList<>(2);
		params.add(new BasicNameValuePair("a", "cancel"));
		params.add(new BasicNameValuePair("id", String.valueOf(id)));

		trade(TRADE_URI, CANCEL_REFERER_URI, params);

		return getDelegations();
	}

	public List<Delegation> getDelegations() throws IOException {
		URI uri;
		try {
			uri = new URIBuilder(TRADE_URI)
				.setParameter("a", "delegation")
				.build();
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
		return httpClient.get(uri, new DelegationReader());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void close() throws IOException {
		httpClient.close();
	}

	private void trade(Type type, BigDecimal price, BigDecimal amount)
			throws IOException {
		List<NameValuePair> params = new ArrayList<>(3);
		params.add(new BasicNameValuePair("a", type.toString()));
		params.add(new BasicNameValuePair("trading", "guding")); // limit price
		params.add(new BasicNameValuePair("price", price.toPlainString()));
		params.add(new BasicNameValuePair("amount", amount.toPlainString()));

		trade(TRADE_URI, TRADE_URI, params);
	}

	private void trade(URI tradeUri, URI referer, List<NameValuePair> params)
			throws IOException {
		TradeResult tradeResult = executeXmlRequest(tradeUri, referer,
				params, TradeResult.class);

		if (tradeResult.getCode() != 0) {
			throw new HUOBIClientException(tradeResult.getMsg());
		}
	}

	private <T> T executeXmlRequest(URI uri, URI referer,
			List<NameValuePair> params, Class<T> objectClass)
			throws IOException {
		HttpPost post = new HttpPost(uri);
		post.setHeader("X-Requested-With", "XMLHttpRequest");
		log.debug("Adding header REferer: {}", referer);
		post.setHeader("Referer", referer.toString());
		post.setEntity(new UrlEncodedFormEntity(params));
		JsonValueReader<T> valueReader = new JsonValueReader<>(
				new ObjectMapper(), objectClass);
		return httpClient.execute(valueReader, post);
	}

	/**
	 * Calls this method before doing login post is required.
	 */
	private void initLoginPage() throws IOException {
		httpClient.get(HTTPS_BASE, VoidValueReader.getInstance());
	}

}
