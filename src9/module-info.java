module dorkbox.vaadin {
    exports dorkbox.vaadin;
    exports dorkbox.vaadin.util;

    requires transitive dorkbox.updates;
    requires transitive org.slf4j;

//    requires static ch.qos.logback.classic;

//    requires vaadin;

    //Vaadin UI components used
    requires transitive flow.data;
    requires transitive flow.server;
    requires transitive vaadin.accordion.flow;
    requires transitive vaadin.accordion;
    requires transitive vaadin.app.layout.flow;
    requires transitive vaadin.app.layout;
    requires transitive vaadin.button.flow;
    requires transitive vaadin.button;
    requires transitive vaadin.charts.flow;
    requires transitive vaadin.charts;
    requires transitive vaadin.checkbox.flow;
    requires transitive vaadin.checkbox;
    requires transitive vaadin.combo.box.flow;
    requires transitive vaadin.combo.box;
    requires transitive vaadin.confirm.dialog.flow;
    requires transitive vaadin.confirm.dialog;
    requires transitive vaadin.context.menu.flow;
    requires transitive vaadin.context.menu;
    requires transitive vaadin.control.state.mixin;
    requires transitive vaadin.cookie.consent.flow;
    requires transitive vaadin.cookie.consent;
    requires transitive vaadin.core;
    requires transitive vaadin.custom.field.flow;
    requires transitive vaadin.custom.field;
    requires transitive vaadin.date.picker.flow;
    requires transitive vaadin.date.picker;
    requires transitive vaadin.details.flow;
    requires transitive vaadin.details;
    requires transitive vaadin.development.mode.detector;
    requires transitive vaadin.dialog.flow;
    requires transitive vaadin.dialog;
    requires transitive vaadin.element.mixin;
    requires transitive vaadin.grid.flow;
    requires transitive vaadin.list.box.flow;
    requires transitive vaadin.list.box;
    requires transitive vaadin.list.mixin;
    requires transitive vaadin.login.flow;
    requires transitive vaadin.login;
    requires transitive vaadin.lumo.theme;
    requires transitive vaadin.ordered.layout.flow;
    requires transitive vaadin.ordered.layout;
    requires transitive vaadin.progress.bar.flow;
    requires transitive vaadin.progress.bar;
    requires transitive vaadin.radio.button.flow;
    requires transitive vaadin.radio.button;
    requires transitive vaadin.text.field.flow;
    requires transitive vaadin.text.field;
    requires transitive vaadin.upload.flow;
    requires transitive vaadin.upload;
    requires transitive vaadin.usage.statistics;

    requires transitive xnio.api;

//    requires com.conversantmedia.disruptor;
    requires transitive io.github.classgraph;

    requires transitive undertow.core;
    requires transitive undertow.servlet;
    requires transitive undertow.websockets.jsr;

    requires transitive kotlin.stdlib;
}
