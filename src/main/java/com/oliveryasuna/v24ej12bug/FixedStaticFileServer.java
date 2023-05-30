package com.oliveryasuna.v24ej12bug;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.internal.DevModeHandler;
import com.vaadin.flow.internal.DevModeHandlerManager;
import com.vaadin.flow.internal.ResponseWriter;
import com.vaadin.flow.server.*;
import com.vaadin.flow.server.frontend.FrontendUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FixedStaticFileServer implements StaticFileHandler {

  // Logging
  //--------------------------------------------------

  private static final Logger LOGGER = LoggerFactory.getLogger(FixedStaticFileServer.class);

  // Static fields
  //--------------------------------------------------

  private static final Pattern APP_THEME_PATTERN = Pattern.compile("^\\/VAADIN\\/themes\\/([\\s\\S]+?)\\/");

  private static final Pattern INCORRECT_WEBJAR_PATH_REGEX = Pattern.compile("^/frontend[-\\w/]*/webjars/");

  private static final String PROPERTY_FIX_INCORRECT_WEBJAR_PATHS = Constants.VAADIN_PREFIX + "fixIncorrectWebjarPaths";

  // Constructors
  //--------------------------------------------------

  public FixedStaticFileServer(final VaadinService vaadinService) {
    super();

    this.vaadinService = vaadinService;
    this.deploymentConfiguration = vaadinService.getDeploymentConfiguration();
    this.responseWriter = new ResponseWriter(this.deploymentConfiguration);
    this.devModeHandler = DevModeHandlerManager.getDevModeHandler(vaadinService).orElse(null);
  }

  // Fields
  //--------------------------------------------------

  private final VaadinService vaadinService;

  private final DeploymentConfiguration deploymentConfiguration;

  private final ResponseWriter responseWriter;

  private final DevModeHandler devModeHandler;

  // Methods
  //--------------------------------------------------

  @Override
  public boolean serveStaticResource(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
    final String filenameWithPath = getRequestFilename(request);

    if(filenameWithPath.endsWith("/")) {
      return false;
    }

    if(HandlerHelper.isPathUnsafe(filenameWithPath)) {
      LOGGER.info("Blocked attempt to access file: {}", filenameWithPath);

      response.setStatus(HttpStatusCode.BAD_REQUEST.getCode());

      return true;
    }

    final boolean isIndexHtml = "/index.html".equals(filenameWithPath);

    if(devModeHandler != null && !isIndexHtml && devModeHandler.serveDevModeRequest(request, response)) {
      return true;
    }

    URL resourceUrl = null;

    if(deploymentConfiguration.getMode() == Mode.DEVELOPMENT_BUNDLE) {
      final File projectFolder = deploymentConfiguration.getProjectFolder();

      if(!isIndexHtml) {
        resourceUrl = FrontendUtils.findBundleFile(projectFolder, "webapp" + filenameWithPath);
      }

      if(resourceUrl == null && (APP_THEME_PATTERN.matcher(filenameWithPath).find() || StaticFileServer.APP_THEME_ASSETS_PATTERN.matcher(filenameWithPath).find())) {
        resourceUrl = findAssetInFrontendThemesOrDevBundle(projectFolder, filenameWithPath.replace(Constants.VAADIN_MAPPING, ""));
      }
    } else if(StaticFileServer.APP_THEME_ASSETS_PATTERN.matcher(filenameWithPath).find()) {
      resourceUrl = vaadinService.getClassLoader().getResource(Constants.VAADIN_WEBAPP_RESOURCES + "VAADIN/static/" + filenameWithPath.replaceFirst("^/", ""));
    } else if(!isIndexHtml) {
      resourceUrl = vaadinService.getClassLoader().getResource(Constants.VAADIN_WEBAPP_RESOURCES + filenameWithPath.replaceFirst("^/", ""));
    }

    if(resourceUrl == null) {
      resourceUrl = getStaticResource(filenameWithPath);
    }

    if(resourceUrl == null && shouldFixIncorrectWebjarPaths() && isIncorrectWebjarPath(filenameWithPath)) {
      resourceUrl = getStaticResource(fixIncorrectWebjarPath(filenameWithPath));
    }

    if(resourceUrl == null) {
      return false;
    }

    if(resourceIsDirectory(resourceUrl)) {
      return false;
    }

    writeCacheHeaders(filenameWithPath, response);

    final long timestamp = writeModificationTimestamp(resourceUrl, request, response);

    if(browserHasNewestVersion(request, timestamp)) {
      response.setStatus(HttpStatusCode.NOT_MODIFIED.getCode());

      return true;
    }

    responseWriter.writeResponseContents(filenameWithPath, resourceUrl, request, response);

    return true;
  }

  private void writeCacheHeaders(final String filenameWithPath, final HttpServletResponse response) {
    final int resourceCacheTime = getCacheTime(filenameWithPath);
    final String cacheControl;

    if(!deploymentConfiguration.isProductionMode()) {
      cacheControl = "no-cache";
    } else if(resourceCacheTime > 0) {
      cacheControl = "max-age=" + resourceCacheTime;
    } else {
      cacheControl = "public, max-age=0, must-revalidate";
    }

    response.setHeader(HttpHeader.CACHE_CONTROL.asString(), cacheControl);
  }

  private int getCacheTime(final String filenameWithPath) {
    if(filenameWithPath.contains(".nocache.")) {
      return 0;
    } else if(filenameWithPath.contains(".cache.")) {
      return (60 * 60 * 24 * 365);
    }

    return 3600;
  }

  private long writeModificationTimestamp(final URL resourceUrl, final HttpServletRequest request, final HttpServletResponse response) {
    URLConnection connection = null;

    try {
      connection = resourceUrl.openConnection();

      long lastModifiedTime = connection.getLastModified();

      lastModifiedTime = lastModifiedTime - (lastModifiedTime % 1000);

      response.setDateHeader(HttpHeader.LAST_MODIFIED.asString(), lastModifiedTime);

      return lastModifiedTime;
    } catch(final IOException e) {
      LOGGER.trace("Failed to get last modified time for resource: {}", resourceUrl, e);
    } finally {
      try {
        if(connection != null) {
          final InputStream input = connection.getInputStream();

          if(input != null) {
            input.close();
          }
        }
      } catch(final IOException e) {
        LOGGER.warn("Failed to close input stream for resource: {}", resourceUrl, e);
      }
    }

    return -1L;
  }

  private boolean browserHasNewestVersion(final HttpServletRequest request, final long resourceLastModifiedTimestamp) {
    assert resourceLastModifiedTimestamp >= -1L;

    if(resourceLastModifiedTimestamp == -1L) {
      return false;
    }

    try {
      final long headerIfModifiedSince = request.getDateHeader(HttpHeader.IF_MODIFIED_SINCE.asString());

      if(headerIfModifiedSince >= resourceLastModifiedTimestamp) {
        return true;
      }
    } catch(final Exception e) {
      LOGGER.trace("Failed to parse If-Modified-Since header", e);
    }

    return false;
  }

  private String getRequestFilename(final HttpServletRequest request) {
    final String pathInfo = request.getPathInfo();

    if(pathInfo == null) {
      return request.getServletPath();
    }

    if(pathInfo.startsWith("/" + Constants.VAADIN_MAPPING) || StaticFileServer.APP_THEME_ASSETS_PATTERN.matcher(pathInfo).find() || pathInfo.startsWith("/sw.js")) {
      return pathInfo;
    }

    return (request.getServletPath() + request.getPathInfo());
  }

  private boolean resourceIsDirectory(final URL resource) {
    if(resource.getPath().endsWith("/")) {
      return true;
    }

    final URI resourceUri;

    try {
      resourceUri = resource.toURI();
    } catch(final URISyntaxException e) {
      LOGGER.debug("Could not convert resource URL to URI: {}", resource, e);

      return false;
    }

    final String resourceProtocol = resource.getProtocol();

    return (("jar".equals(resourceProtocol) || "file".equals(resourceProtocol)) && Files.isDirectory(Paths.get(resourceUri)));
  }

  private URL findAssetInFrontendThemesOrDevBundle(final File projectFolder, final String assetPath) throws IOException {
    final File frontendFolder = new File(projectFolder, FrontendUtils.FRONTEND);

    File assetInFrontendThemes = new File(frontendFolder, assetPath);

    if(assetInFrontendThemes.exists()) {
      return assetInFrontendThemes.toURI().toURL();
    }

    final File jarResourcesFolder = FrontendUtils.getJarResourcesFolder(frontendFolder);

    assetInFrontendThemes = new File(jarResourcesFolder, assetPath);

    if(assetInFrontendThemes.exists()) {
      return assetInFrontendThemes.toURI().toURL();
    }

    final Matcher matcher = StaticFileServer.APP_THEME_ASSETS_PATTERN.matcher(assetPath);

    if(!matcher.find()) {
      throw new IllegalStateException("Asset path '" + assetPath + "' does not match the pattern '" + StaticFileServer.APP_THEME_ASSETS_PATTERN.pattern() + "'.");
    }

    final String themeName = matcher.group(1);

    String defaultBundleAssetPath = assetPath.replaceFirst(themeName, Constants.DEV_BUNDLE_NAME);
    URL assetInDevBundleUrl = vaadinService.getClassLoader().getResource(Constants.DEV_BUNDLE_JAR_PATH + Constants.ASSETS + defaultBundleAssetPath);

    if(assetInDevBundleUrl == null) {
      final String assetInDevBundle = "/" + Constants.ASSETS + "/" + assetPath;

      assetInDevBundleUrl = FrontendUtils.findBundleFile(projectFolder, assetInDevBundle);
    }

    if(assetInDevBundleUrl == null) {
      final String assetName = assetPath.substring(assetPath.indexOf(themeName) + themeName.length());

      throw new IllegalStateException(String.format("" +
              "Asset '%1$s' is not found in project frontend directory"
              + ", default development bundle or in the application "
              + "bundle '%2$s/assets/'. \n"
              + "Verify that the asset is available in "
              + "'frontend/themes/%3$s/' directory and is added into the "
              + "'assets' block of the 'theme.json' file.",
          assetName, Constants.DEV_BUNDLE_LOCATION, themeName));
    }

    return assetInDevBundleUrl;
  }

  private URL getStaticResource(final String path) {
    return vaadinService.getStaticResource(path);
  }

  private String fixIncorrectWebjarPath(final String requestFilename) {
    return INCORRECT_WEBJAR_PATH_REGEX.matcher(requestFilename)
        .replaceAll("/webjars/");
  }

  private boolean isIncorrectWebjarPath(final String requestFilename) {
    return INCORRECT_WEBJAR_PATH_REGEX.matcher(requestFilename).lookingAt();
  }

  private boolean shouldFixIncorrectWebjarPaths() {
    return (deploymentConfiguration.isProductionMode() && deploymentConfiguration.getBooleanProperty(PROPERTY_FIX_INCORRECT_WEBJAR_PATHS, false));
  }

}
