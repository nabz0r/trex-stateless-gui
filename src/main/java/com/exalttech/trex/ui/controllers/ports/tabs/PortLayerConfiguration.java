package com.exalttech.trex.ui.controllers.ports.tabs;

import com.cisco.trex.stateless.IPv6NeighborDiscoveryService;
import com.cisco.trex.stateless.TRexClient;
import com.cisco.trex.stateless.exception.ServiceModeRequiredException;
import com.cisco.trex.stateless.model.Ipv6Node;
import com.cisco.trex.stateless.model.StubResult;
import com.cisco.trex.stateless.model.TRexClientResult;
import com.cisco.trex.stl.gui.models.IPv6Host;
import com.exalttech.trex.application.TrexApp;
import com.exalttech.trex.core.AsyncResponseManager;
import com.exalttech.trex.core.ConnectionManager;
import com.exalttech.trex.core.RPCMethods;
import com.exalttech.trex.ui.models.ConfigurationMode;
import com.exalttech.trex.ui.models.PortLayerConfigurationModel;
import com.exalttech.trex.ui.models.PortModel;
import com.exalttech.trex.ui.views.logs.LogType;
import com.exalttech.trex.ui.views.logs.LogsController;
import com.exalttech.trex.util.Initialization;
import com.google.common.base.Strings;
import com.google.common.net.InetAddresses;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.AnchorPane;
import org.apache.log4j.Logger;
import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.IcmpV4CommonPacket;
import org.pcap4j.packet.IcmpV6CommonPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.namednumber.IcmpV4Type;
import org.pcap4j.packet.namednumber.IcmpV6Type;

import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class PortLayerConfiguration extends AnchorPane {
    @FXML
    private AnchorPane root;
    @FXML
    private ToggleGroup mode;

    @FXML
    private TextField l2Source;
    @FXML
    private TextField l3Source;

    @FXML
    private TextField l2Destination;

    @FXML
    private TextField l3Destination;

    @FXML
    private Label pingLabel;

    @FXML
    private TextField pingDestination;

    @FXML
    private Button pingCommandBtn;

    @FXML
    private Button saveBtn;

    @FXML
    private Button startScanIpv6Btn;

    @FXML
    private Button clearIpv6HostsBtn;

    @FXML
    private Label arpStatus;

    @FXML
    private Label ipv6NDStatus;

    @FXML
    private Label arpLabel;

    @FXML
    private RadioButton l2Mode;

    @FXML
    private RadioButton l3Mode;

    @FXML
    private TextField vlan;

    @FXML
    private TableView<IPv6Host> ipv6Hosts;

    @FXML
    private Label ipv6HostsPlaceholder;
    private Label ipv6HostsDefaultPlaceholder;

    @FXML
    private TableColumn<IPv6Host, String> macColumn;

    @FXML
    private TableColumn<IPv6Host, String> ipv6Column;

    private IPv6NeighborDiscoveryService iPv6NDService;

    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    Clipboard clipboard = Clipboard.getSystemClipboard();

    private PortModel model;
    private LogsController guiLogger = LogsController.getInstance();
    private RPCMethods serverRPCMethods = TrexApp.injector.getInstance(RPCMethods.class);
    private static final Logger logger = Logger.getLogger(PortLayerConfiguration.class);
    private ChangeListener<ConfigurationMode> configurationModeChangeListener = (observable, prevMode, mode) -> updateControlsState();
    private Label ipv6HostsNotFoundPlaceholder = new Label("Zero IPv6 hosts found. Try to scan once again.");

    private void updateControlsState() {
        Arrays.asList(l2Source, l2Destination, l3Source, l3Destination).forEach(textField -> {
            textField.setVisible(false);
            textField.setManaged(false);
        });

        if (ConfigurationMode.L2.equals(model.getLayerMode())) {
            l2Source.setVisible(true);
            l2Source.setManaged(true);

            l2Destination.setVisible(true);
            l2Destination.setManaged(true);

            l2Mode.setSelected(true);

            arpStatus.setVisible(false);
            arpLabel.setVisible(false);
        } else {
            l3Source.setVisible(true);
            l3Source.setManaged(true);

            l3Destination.setVisible(true);
            l3Destination.setManaged(true);

            arpStatus.textProperty().bindBidirectional(model.getL3LayerConfiguration().stateProperty());
            l3Mode.setSelected(true);
            arpLabel.setVisible(true);
            arpStatus.setVisible(true);
        }
    }

    public PortLayerConfiguration() {
        Initialization.initializeFXML(this, "/fxml/ports/PortLayerConfiguration.fxml");

        l2Mode.setOnAction(event -> model.setLayerMode(ConfigurationMode.L2));

        l3Mode.setOnAction(event -> model.setLayerMode(ConfigurationMode.L3));

        pingCommandBtn.setOnAction(this::runPingCmd);

        saveBtn.setOnAction(this::saveConfiguration);

        macColumn.setCellValueFactory(cellData -> cellData.getValue().macAddressProperty());

        ipv6Column.setCellValueFactory(cellData -> cellData.getValue().ipAddressProperty());

        ipv6Hosts.setRowFactory(tv -> {
            TableRow<IPv6Host> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    this.setAsL2DstAction(row.getItem().getMacAddress());
                }
            });
            ContextMenu ctxMenu = new ContextMenu();

            ctxMenu.getItems().addAll(createMenuItems(row));
            row.setContextMenu(ctxMenu);

            row.contextMenuProperty().bind(
                Bindings.when(Bindings.isNotNull(row.itemProperty()))
                    .then(ctxMenu)
                    .otherwise((ContextMenu) null));
            return row;
        });

        startScanIpv6Btn.setOnAction(this::handleStartIPv6Scan);

        clearIpv6HostsBtn.setOnAction(e -> {
            ipv6Hosts.getItems().clear();
            ipv6Hosts.setPlaceholder(ipv6HostsDefaultPlaceholder);
        });

        ipv6HostsDefaultPlaceholder = ipv6HostsPlaceholder;
    }

    private ObservableList<MenuItem> createMenuItems(TableRow<IPv6Host> row) {
        ObservableList<MenuItem> ctxMenuItems = FXCollections.observableArrayList();

        MenuItem setAsL2DstMenuItem = new MenuItem("Set as L2 destination");
        setAsL2DstMenuItem.setOnAction(e -> setAsL2DstAction(row.getItem().getMacAddress()));

        MenuItem copyMacMenuItem = new MenuItem("Copy MAC");
        copyMacMenuItem.setOnAction(e -> {
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(row.getItem().getMacAddress());
            clipboard.setContent(clipboardContent);
        });
        MenuItem copyIPMenuItem = new MenuItem("Copy IP");
        copyIPMenuItem.setOnAction(e -> {
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(row.getItem().getIpAddress());
            clipboard.setContent(clipboardContent);
        });


        ctxMenuItems.addAll(setAsL2DstMenuItem, copyMacMenuItem, copyIPMenuItem);

        return ctxMenuItems;
    }

    private void setAsL2DstAction(String mac) {
        l2Mode.setSelected(true);
        model.setLayerMode(ConfigurationMode.L2);
        l2Destination.setText(mac);
    }


    private void handleStartIPv6Scan(ActionEvent actionEvent) {
        LogsController.getInstance().appendText(LogType.INFO, "Start scanning IPv6 neighbor hosts.");
        AsyncResponseManager.getInstance().muteLogger();
        AsyncResponseManager.getInstance().suppressIncomingEvents(true);


        boolean multicastEnabled = model.getMulticast();
        if (!multicastEnabled) {
            model.multicastProperty().setValue(true);
        }
        getIPv6NDService();
        startScanIpv6Btn.setDisable(true);
        ipv6Hosts.getItems().clear();
        ipv6Hosts.setPlaceholder(new Label("Scanning in progress..."));
        Task<Optional<Map<String, Ipv6Node>>> scanIpv6NeighborsTask = new Task<Optional<Map<String, Ipv6Node>>>() {
            @Override
            public Optional<Map<String, Ipv6Node>> call() {
                try {
                    return Optional.of(iPv6NDService.scan(model.getIndex(), 10, null, null));
                } catch (ServiceModeRequiredException e) {
                    AsyncResponseManager.getInstance().unmuteLogger();
                    AsyncResponseManager.getInstance().suppressIncomingEvents(false);
                    Platform.runLater(() -> {
                        ipv6Hosts.setPlaceholder(ipv6HostsDefaultPlaceholder);
                        LogsController.getInstance().appendText(LogType.ERROR, "Service mode is not enabled for port: " + model.getIndex() + ". Enable Service Mode in Control tab.");
                    });
                }
                return Optional.empty();
            }
        };
        scanIpv6NeighborsTask.setOnSucceeded(e -> {
            AsyncResponseManager.getInstance().unmuteLogger();
            AsyncResponseManager.getInstance().suppressIncomingEvents(false);
            startScanIpv6Btn.setDisable(false);

            Optional<Map<String, Ipv6Node>> result = scanIpv6NeighborsTask.getValue();
            result.ifPresent((hosts) -> {
                ipv6Hosts.getItems().addAll(
                    hosts.entrySet().stream()
                        .map(entry -> new IPv6Host(entry.getValue().getMac(), entry.getValue().getIp()))
                        .collect(Collectors.toList())
                );

                if (hosts.isEmpty()) {
                    ipv6Hosts.setPlaceholder(ipv6HostsNotFoundPlaceholder);
                }
                LogsController.getInstance().appendText(LogType.INFO, "Found " + hosts.size() + " nodes.");
                LogsController.getInstance().appendText(LogType.INFO, "Scanning complete.");
            });

            if (!multicastEnabled) {
                model.multicastProperty().setValue(false);
            }
        });

        executorService.submit(scanIpv6NeighborsTask);
    }

    private void saveConfiguration(Event event) {

        if (model.getPortStatus().equalsIgnoreCase("tx")) {
            guiLogger.appendText(LogType.ERROR, "Port " + model.getIndex() + " is in TX mode. Please stop traffic first.");
            return;
        }
        int vlanIdsCount = Arrays.stream(model.getVlan().split(" "))
            .filter(vlanId -> !Strings.isNullOrEmpty(vlanId))
            .map(Integer::valueOf)
            .collect(Collectors.toList())
            .size();

        if (vlanIdsCount > 2) {
            guiLogger.appendText(LogType.ERROR, "Maximum two nested VLAN tags are allowed.");
            return;
        }

        if (l2Mode.isSelected()) {
            if (Strings.isNullOrEmpty(l2Destination.getText())) {
                guiLogger.appendText(LogType.ERROR, "Destination MAC is empty. ");
                return;
            }
        } else {
            final String dstAddress = l3Destination.getText();

            if (!validateIpAddress(dstAddress)) {
                return;
            }

            boolean isDstIPv4 = InetAddresses.forString(dstAddress).getAddress().length == 4;

            if (isDstIPv4 && !validateIpv4Address(l3Source.getText())) {
                return;
            }
        }

        saveBtn.setDisable(true);
        saveBtn.setText("Applying...");
        Task saveConfigurationTask = new Task<Optional<String>>() {
            @Override
            public Optional<String> call() {
                TRexClient trexClient = ConnectionManager.getInstance().getTrexClient();

                List<Integer> vlanIds = new ArrayList<>();
                if (!Strings.isNullOrEmpty(model.getVlan())) {
                    vlanIds.addAll(
                        Arrays.stream(model.getVlan().split(" "))
                              .filter(vlanId -> !Strings.isNullOrEmpty(vlanId))
                              .map(Integer::valueOf)
                              .collect(Collectors.toList())
                    );
                }

                AsyncResponseManager.getInstance().suppressIncomingEvents(true);
                trexClient.serviceMode(model.getIndex(), true);

                TRexClientResult<StubResult> vlanConfigResult = trexClient.setVlan(model.getIndex(), vlanIds);
                if (vlanConfigResult.isFailed()) {
                    guiLogger.appendText(LogType.ERROR, "Unable to save VLAN configuration");
                } else {
                    guiLogger.appendText(LogType.INFO, "VLAN configuration updated");
                }

                if (l2Mode.isSelected()) {
                    String dstMac = l2Destination.getText();
                    try {
                        serverRPCMethods.setSetL2(model.getIndex(), dstMac);
                        guiLogger.appendText(LogType.INFO, "L2 mode configured for " + model.getIndex());
                        clearModelIPv6();
                    } catch (Exception e1) {
                        logger.error("Failed to set L2 mode: " + e1.getMessage());
                    }
                } else if (l3Mode.isSelected()) {
                    try {
                        AsyncResponseManager.getInstance().muteLogger();
                        String portSrcIP = l3Source.getText();
                        String portDstIP = l3Destination.getText();
                        final boolean dstIsIPv6 = InetAddresses.forString(portDstIP).getAddress().length > 4;
                        final boolean srcIsIPv6 = InetAddresses.forString(portSrcIP).getAddress().length > 4;

                        if (srcIsIPv6 != dstIsIPv6) {
                            throw new Exception("src and dst addresses are different version");
                        }

                        if (dstIsIPv6) {
                            Map<String, Ipv6Node> result = getIPv6NDService().scan(model.getIndex(), 5, portDstIP, portSrcIP);
                            AsyncResponseManager.getInstance().unmuteLogger();

                            String statusString;
                            String logEntry;
                            if (result.size() == 1) {
                                Ipv6Node host = result.entrySet().iterator().next().getValue();
                                final String mac = host.getMac();
                                statusString = "resolved";
                                logEntry = "IPv6 destination resolved: " + mac;

                                setAsL2DstAction(mac);

                                try {
                                    serverRPCMethods.setSetL2(model.getIndex(), mac);
                                    guiLogger.appendText(LogType.INFO, "L2 mode configured for " + model.getIndex());

                                    final PortLayerConfigurationModel l3Conf = model.getL3LayerConfiguration();
                                    l3Conf.setSrc6(portSrcIP);
                                    l3Conf.setDst6(portDstIP);
                                } catch (Exception e1) {
                                    logger.error("Failed to set L2 mode: " + e1.getMessage());
                                }
                            } else {
                                statusString = "-";
                                logEntry = "Unable to resolve IPv6 destination.";
                            }
                            Platform.runLater(() -> {
                                arpStatus.setText("none");
                                ipv6NDStatus.setText(statusString);
                                guiLogger.appendText(LogType.INFO, logEntry);
                            });
                        } else {
                            trexClient.setL3Mode(model.getIndex(), null, portSrcIP, portDstIP);

                            clearModelIPv6();
                            String nextHopMac = trexClient.resolveArp(model.getIndex(), portSrcIP, portDstIP);
                            if (nextHopMac != null) {
                                trexClient.setL3Mode(model.getIndex(), nextHopMac, portSrcIP, portDstIP);
                            }

                            Platform.runLater(() -> {
                                ipv6NDStatus.setText("-");
                            });

                            return nextHopMac == null ? Optional.empty() : Optional.of(nextHopMac);
                        }
                    } catch (ServiceModeRequiredException e) {
                        AsyncResponseManager.getInstance().unmuteLogger();
                        Platform.runLater(() -> {
                            ipv6Hosts.setPlaceholder(ipv6HostsDefaultPlaceholder);
                            LogsController.getInstance().appendText(LogType.ERROR, "Service mode is not enabled for port: " + model.getIndex() + ". Enable Service Mode in Control tab.");
                        });
                    } catch (Exception e) {
                        logger.error("Failed to set L3 mode: " + e.getMessage());
                        guiLogger.appendText(LogType.ERROR, "Failed to set L3 mode: " + e.getMessage());
                    } finally {
                        trexClient.serviceMode(model.getIndex(), false);
                        AsyncResponseManager.getInstance().suppressIncomingEvents(false);
                        AsyncResponseManager.getInstance().unmuteLogger();
                    }
                }
                return Optional.empty();
            }
        };

        saveConfigurationTask.setOnSucceeded(e -> {
            saveBtn.setText("Apply");
            saveBtn.setDisable(false);

            final boolean dstIsIPv6 = InetAddresses.forString(l3Source.getText()).getAddress().length > 4;
            final boolean srcIsIPv6 = InetAddresses.forString(l3Destination.getText()).getAddress().length > 4;

            Optional result = (Optional) (saveConfigurationTask.getValue());
            if (l3Mode.isSelected() && !dstIsIPv6 && !srcIsIPv6) {
                String status = "unresolved";
                if (result.isPresent()) {
                    status = "resolved";
                    guiLogger.appendText(LogType.INFO, "ARP resolution for " + l3Destination.getText() + " is " + result.get());
                } else {
                    guiLogger.appendText(LogType.ERROR, "ARP resolution arpStatus: FAILED");
                }
                this.arpStatus.setText(status);
            }
        });

        new Thread(saveConfigurationTask).start();
    }

    private IPv6NeighborDiscoveryService getIPv6NDService() {
        if (iPv6NDService == null) {
            TRexClient trexClient = ConnectionManager.getInstance().getTrexClient();
            iPv6NDService = new IPv6NeighborDiscoveryService(trexClient);
        }
        return iPv6NDService;
    }

    private boolean validateIpAddress(String ip) {
        if (Strings.isNullOrEmpty(ip)) {
            guiLogger.appendText(LogType.ERROR, "Empty IP address.");
            return false;
        }
        try {
            InetAddresses.forString(ip);
            return true;
        } catch (IllegalArgumentException e) {
            guiLogger.appendText(LogType.ERROR, "Malformed IP address.");
        }
        return false;
    }

    private boolean validateIpv4Address(String ip) {
        if (Strings.isNullOrEmpty(ip)) {
            guiLogger.appendText(LogType.ERROR, "Empty IP address.");
            return false;
        }
        try {
            byte[] addr = InetAddresses.forString(ip).getAddress();

            if (addr.length > 4) {
                guiLogger.appendText(LogType.ERROR, "Source IPv6 addresses are not supported.");
                return false;
            }

            return true;
        } catch (IllegalArgumentException e) {
            guiLogger.appendText(LogType.ERROR, "Malformed IP address.");
        }
        return false;
    }

    private void runPingCmd(Event event) {
        if (model.getPortStatus().equalsIgnoreCase("tx")) {
            guiLogger.appendText(LogType.ERROR, "Port " + model.getIndex() + " is in TX mode. Please stop traffic first.");
            return;
        }

        if (Strings.isNullOrEmpty(pingDestination.getText())) {
            guiLogger.appendText(LogType.ERROR, "Empty ping destination address.");
            return;
        }
        final String targetIP = pingDestination.getText();
        if (!targetIP.contains(":") && !model.getL3LayerConfiguration().getState().equalsIgnoreCase("resolved")) {
            guiLogger.appendText(LogType.ERROR, "ARP resolution required. Configure L3IPv4 mode properly.");
            return;
        }

        pingCommandBtn.setDisable(true);

        Task<Void> pingTask = new Task<Void>() {
            @Override
            public Void call() {
                TRexClient trexClient = ConnectionManager.getInstance().getTrexClient();
                trexClient.serviceMode(model.getIndex(), true);
                guiLogger.appendText(LogType.PING, " Start ping " + targetIP);

                AsyncResponseManager.getInstance().muteLogger();
                AsyncResponseManager.getInstance().suppressIncomingEvents(true);
                try {
                    int icmp_id = new Random().nextInt(100);
                    for (int icmp_sec = 1; icmp_sec < 6; icmp_sec++) {
                        EthernetPacket reply = null;
                        if (targetIP.contains(":")) {
                            // IPv6
                            reply = trexClient.sendIcmpV6Echo(model.getIndex(), targetIP, icmp_id, icmp_sec, 2);
                            if (reply != null) {
                                IcmpV6CommonPacket icmpV6CommonPacket = reply.get(IcmpV6CommonPacket.class);
                                IcmpV6Type icmpReplyType = icmpV6CommonPacket.getHeader().getType();
                                String msg = null;
                                if (IcmpV6Type.ECHO_REPLY.equals(icmpReplyType)) {
                                    msg = " Reply from " + targetIP + " size=" + reply.getRawData().length + " icmp_sec=" + icmp_sec;
                                } else if (IcmpV6Type.DESTINATION_UNREACHABLE.equals(icmpReplyType)) {
                                    msg = " Destination host unreachable";
                                }
                                guiLogger.appendText(LogType.PING, msg);
                            } else {
                                guiLogger.appendText(LogType.PING, "Request timeout.");
                            }
                        } else {
                            // IPv4
                            reply = trexClient.sendIcmpEcho(model.getIndex(), targetIP, icmp_id, icmp_sec, 1000);
                            if (reply != null) {
                                IpV4Packet ip = reply.get(IpV4Packet.class);
                                String ttl = String.valueOf(ip.getHeader().getTtlAsInt());
                                IcmpV4CommonPacket echoReplyPacket = reply.get(IcmpV4CommonPacket.class);
                                IcmpV4Type replyType = echoReplyPacket.getHeader().getType();
                                if (IcmpV4Type.ECHO_REPLY.equals(replyType)) {
                                    guiLogger.appendText(LogType.PING, " Reply from " + targetIP + " size=" + reply.getRawData().length + " ttl=" + ttl + " icmp_sec=" + icmp_sec);
                                } else if (IcmpV4Type.DESTINATION_UNREACHABLE.equals(replyType)) {
                                    guiLogger.appendText(LogType.PING, " Destination host unreachable");
                                }
                            } else {
                                guiLogger.appendText(LogType.PING, " Request timeout for icmp_seq " + icmp_sec);
                            }
                        }
                    }
                    guiLogger.appendText(LogType.PING, " Ping finished.");
                } catch (UnknownHostException e) {
                    guiLogger.appendText(LogType.PING, " Unknown host");
                } catch (ServiceModeRequiredException e) {
                    e.printStackTrace();
                } finally {
                    pingCommandBtn.setDisable(false);
                    trexClient.serviceMode(model.getIndex(), false);
                    AsyncResponseManager.getInstance().unmuteLogger();
                    AsyncResponseManager.getInstance().suppressIncomingEvents(false);
                }
                return null;
            }
        };

        pingTask.setOnSucceeded(e -> pingCommandBtn.setDisable(false));

        new Thread(pingTask).start();
    }

    public void bindModel(PortModel model) {
        unbindAll();
        this.model = model;

        l2Destination.textProperty().bindBidirectional(this.model.getL2LayerConfiguration().dstProperty());
        l2Source.textProperty().bindBidirectional(this.model.getL2LayerConfiguration().srcProperty());

        final PortLayerConfigurationModel l3Conf = this.model.getL3LayerConfiguration();

        l3Destination.textProperty().bindBidirectional(l3Conf.dstProperty());
        l3Source.textProperty().bindBidirectional(l3Conf.srcProperty());

        final String src6 = l3Conf.getSrc6();
        final String dst6 = l3Conf.getDst6();

        if (src6 != null && !src6.equals("")) {
            l3Source.textProperty().setValue(src6);
        }

        if (dst6 != null && !dst6.equals("")) {
            l3Destination.textProperty().setValue(dst6);
        }

        vlan.textProperty().bindBidirectional(this.model.vlanProperty());

        updateControlsState();

        this.model.layerConfigurationTypeProperty().addListener(configurationModeChangeListener);
    }

    private void unbindAll() {
        if (model == null) {
            return;
        }
        vlan.textProperty().unbindBidirectional(this.model.vlanProperty());

        l2Destination.textProperty().unbindBidirectional(this.model.getL2LayerConfiguration().dstProperty());
        l2Source.textProperty().unbindBidirectional(this.model.getL2LayerConfiguration().srcProperty());

        l3Destination.textProperty().unbindBidirectional(this.model.getL3LayerConfiguration().dstProperty());
        l3Source.textProperty().unbindBidirectional(this.model.getL3LayerConfiguration().srcProperty());

        arpStatus.textProperty().unbindBidirectional(model.getL3LayerConfiguration().stateProperty());
        model.layerConfigurationTypeProperty().removeListener(configurationModeChangeListener);
    }

    private void clearModelIPv6() {
        final PortLayerConfigurationModel l3Conf = this.model.getL3LayerConfiguration();
        l3Conf.setSrc6(null);
        l3Conf.setDst6(null);
    }
}
