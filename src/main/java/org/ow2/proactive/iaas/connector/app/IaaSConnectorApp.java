package org.ow2.proactive.iaas.connector.app;import com.aol.micro.server.MicroserverApp;import com.aol.micro.server.config.Microserver;import lombok.AccessLevel;import lombok.NoArgsConstructor;@NoArgsConstructor(access = AccessLevel.PRIVATE)@Microserver(basePackages = { "org.ow2.proactive.iaas.connector" })public class IaaSConnectorApp {	public static void main(String[] args) throws InterruptedException {		new MicroserverApp(() -> "iaas-connector").run();	}}