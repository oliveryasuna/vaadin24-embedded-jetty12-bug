package com.oliveryasuna.v24ej12bug;

import com.vaadin.flow.server.StaticFileHandler;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServlet;

public final class FixedVaadinServlet extends VaadinServlet {

  // Constructors
  //--------------------------------------------------

  public FixedVaadinServlet() {
    super();
  }

  // Methods
  //--------------------------------------------------

  @Override
  protected StaticFileHandler createStaticFileHandler(final VaadinService vaadinService) {
    return new FixedStaticFileServer(vaadinService);
  }

}
