package com.oliveryasuna.v24ej12bug;

import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.ResourceFactory;

public final class Application {

  // Entry point
  //--------------------------------------------------

  public static void main(final String[] args) throws Exception {
    final Server server = new Server(8080);
    final WebAppContext context = new WebAppContext();

    context.setBaseResource(ResourceFactory.root().newResource(Application.class.getResource("/webapp")));
    context.setContextPath("/");
    context.setExtractWAR(false);

    final ServletHolder vaadinServletHolder = context.addServlet(FixedVaadinServlet.class, "/*");

    vaadinServletHolder.setInitOrder(1);
    vaadinServletHolder.setInitParameter("pushMode", "automatic");

    context.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern", ".*\\.jar|.*/classes/.*");
    context.setConfigurationDiscovered(true);
    context.setParentLoaderPriority(true);

    server.setHandler(context);

    server.start();
    server.join();
  }

}
