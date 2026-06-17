module com.sharewave {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires java.desktop;

    // Jetty
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.servlet;
    requires org.eclipse.jetty.util;
    requires jetty.servlet.api;

    requires org.xerial.sqlitejdbc;
    requires jbcrypt;
    requires com.google.gson;

    exports com.sharewave;
}
