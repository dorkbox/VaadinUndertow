module dorkbox.vaadin {
    exports dorkbox.vaadin;
    exports dorkbox.vaadin.util;

    requires dorkbox.updates;
    requires org.slf4j;

//    requires static ch.qos.logback.classic;

//    requires vaadin;

    //Vaadin UI components used
    requires flow.data;
    requires flow.server;
    requires vaadin.accordion.flow;
    requires vaadin.accordion;
    requires vaadin.app.layout.flow;
    requires vaadin.app.layout;
    requires vaadin.button.flow;
    requires vaadin.button;
    requires vaadin.charts.flow;
    requires vaadin.charts;
    requires vaadin.checkbox.flow;
    requires vaadin.checkbox;
    requires vaadin.combo.box.flow;
    requires vaadin.combo.box;
    requires vaadin.confirm.dialog.flow;
    requires vaadin.confirm.dialog;
    requires vaadin.context.menu.flow;
    requires vaadin.context.menu;
    requires vaadin.control.state.mixin;
    requires vaadin.cookie.consent.flow;
    requires vaadin.cookie.consent;
    requires vaadin.core;
    requires vaadin.custom.field.flow;
    requires vaadin.custom.field;
    requires vaadin.date.picker.flow;
    requires vaadin.date.picker;
    requires vaadin.details.flow;
    requires vaadin.details;
    requires vaadin.development.mode.detector;
    requires vaadin.dialog.flow;
    requires vaadin.dialog;
    requires vaadin.element.mixin;
    requires vaadin.grid.flow;
    requires vaadin.list.box.flow;
    requires vaadin.list.box;
    requires vaadin.list.mixin;
    requires vaadin.login.flow;
    requires vaadin.login;
    requires vaadin.lumo.theme;
    requires vaadin.ordered.layout.flow;
    requires vaadin.ordered.layout;
    requires vaadin.progress.bar.flow;
    requires vaadin.progress.bar;
    requires vaadin.radio.button.flow;
    requires vaadin.radio.button;
    requires vaadin.text.field.flow;
    requires vaadin.text.field;
    requires vaadin.upload.flow;
    requires vaadin.upload;
    requires vaadin.usage.statistics;

    requires xnio.api;

//    requires com.conversantmedia.disruptor;
    requires io.github.classgraph;

    requires undertow.core;
    requires undertow.servlet;
    requires undertow.websockets.jsr;

    requires kotlin.stdlib;
    requires java.base;
}
