package com.logicaldoc.web.filter;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Healdan: the shipped {@code cachecontrol} setting. GWT's
 * {@code *.nocache.js} loaders name the build's {@code *.cache.js} files, so a
 * browser must check them on every load; under {@code *.js}'s ten minutes it
 * kept running the old GUI after an upgrade. The other rules are upstream's.
 */
public class CacheControlFilterTest {

	@BeforeClass
	public static void loadShippedSetting() throws IOException {
		Properties shipped = new Properties();
		try (InputStream in = new FileInputStream("src/main/resources/context.properties")) {
			shipped.load(in);
		}
		CacheControlFilter.init(shipped.getProperty("cachecontrol"));
	}

	@Test
	public void testLoadersAreRevalidated() throws IOException, ServletException {
		assertEquals("no-cache", cacheControl("/frontend/frontend.nocache.js"));
		assertEquals("no-cache", cacheControl("/login/login.nocache.js"));
		assertEquals("no-cache", cacheControl("/setup/setup.nocache.js"));
	}

	@Test
	public void testOtherFilesKeepUpstreamRules() throws IOException, ServletException {
		assertEquals("max-age=600", cacheControl("/frontend/5A1F0B0C2E8D4F6A9B3C7D1E2F405162.cache.js"));
		assertEquals("max-age=600", cacheControl("/frontend/deferredjs/5A1F0B0C2E8D4F6A9B3C7D1E2F405162/1.cache.js"));
		assertEquals("max-age=600", cacheControl("/frontend/sc/modules/ISC_Core.js"));
		assertEquals("max-age=2592000", cacheControl("/frontend/sc/skins/Tahoe/images/blank.gif"));
		assertEquals("max-age=0", cacheControl("/frontend.jsp"));
		assertEquals("no-cache,no-store,must-revalidate", cacheControl("/api/v1/server"));
	}

	private static String cacheControl(String uri) throws IOException, ServletException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn(uri);
		HttpServletResponse response = mock(HttpServletResponse.class);

		new CacheControlFilter().doFilter(request, response, mock(FilterChain.class));

		ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
		verify(response).setHeader(eq("Cache-Control"), value.capture());
		return value.getValue();
	}
}
