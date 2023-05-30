package com.oliveryasuna.v24ej12bug;

import com.vaadin.flow.component.Unit;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

@Route("/")
@CssImport("./root-view.css")
public final class RootView extends VerticalLayout {

  // Constructors
  //--------------------------------------------------

  public RootView() {
    super();

    final Image image = new Image("images/duck.png", "Duck");

    image.setWidth(400, Unit.PIXELS);

    add(image);

    setSizeFull();
    setAlignItems(Alignment.CENTER);
    setJustifyContentMode(JustifyContentMode.CENTER);
    addClassName("root-view");
  }

}
