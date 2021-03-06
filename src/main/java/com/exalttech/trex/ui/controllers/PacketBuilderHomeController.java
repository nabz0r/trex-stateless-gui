/**
 * *****************************************************************************
 * Copyright (c) 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************
 */
package com.exalttech.trex.ui.controllers;

import com.exalttech.trex.ui.util.TrexAlertBuilder;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import com.google.common.base.Strings;
import com.google.common.eventbus.EventBus;
import com.google.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Base64;
import java.util.List;
import java.util.ResourceBundle;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.xored.javafx.packeteditor.controllers.FieldEditorController;
import com.xored.javafx.packeteditor.events.ScapyClientNeedConnectEvent;

import com.exalttech.trex.core.ConnectionManager;
import com.exalttech.trex.remote.models.profiles.Packet;
import com.exalttech.trex.remote.models.profiles.Profile;
import com.exalttech.trex.remote.models.profiles.Stream;
import com.exalttech.trex.ui.StreamBuilderType;
import com.exalttech.trex.ui.dialog.DialogView;
import com.exalttech.trex.ui.models.PacketInfo;
import com.exalttech.trex.ui.views.streams.binders.BuilderDataBinding;
import com.exalttech.trex.ui.views.streams.builder.PacketBuilderHelper;
import com.exalttech.trex.ui.views.streams.viewer.PacketHex;
import com.exalttech.trex.ui.views.streams.viewer.PacketParser;
import com.exalttech.trex.util.TrafficProfile;
import com.exalttech.trex.util.Util;


public class PacketBuilderHomeController extends DialogView implements Initializable {
    private static final Logger LOG = Logger.getLogger(PacketBuilderHomeController.class.getName());

    @FXML
    private AnchorPane hexPane;
    @FXML
    private Button nextStreamBtn;
    @FXML
    private Button streamEditorModeBtn;
    @FXML
    private Button prevStreamBtn;
    @FXML
    private Button saveButton;
    @FXML
    private StreamPropertiesViewController streamPropertiesController;
    @FXML
    private PacketViewerController packetViewerController;
    @FXML
    private ProtocolSelectionController protocolSelectionController;
    @FXML
    private ProtocolDataController protocolDataController;
    @FXML
    private Tab packetViewerTab;
    @FXML
    private Tab packetEditorTab;
    @FXML
    private Tab fieldEngineTab;
    @FXML
    private Tab packetViewerWithTreeTab;
    @FXML
    private Tab protocolSelectionTab;
    @FXML
    private Tab protocolDataTab;
    @FXML
    private Tab advanceSettingsTab;
    @FXML
    private AdvancedSettingsController advancedSettingsController;
    @FXML
    private Tab streamPropertiesTab;
    @FXML
    private TabPane streamTabPane;

    @Inject
    private FieldEditorController packetBuilderController;
    @Inject
    private EventBus eventBus;

    private PacketInfo packetInfo = null;
    private PacketParser parser;
    private PacketHex packetHex;
    private Profile selectedProfile;
    private boolean isBuildPacket = false;
    private List<Profile> profileList;
    private String yamlFileName;
    private int currentSelectedProfileIndex;
    private BuilderDataBinding builderDataBinder;
    private TrafficProfile trafficProfile;
    private BooleanProperty isImportedStreamProperty = new SimpleBooleanProperty(false);

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        trafficProfile = new TrafficProfile();
        packetHex = new PacketHex(hexPane);
        nextStreamBtn.setGraphic(new ImageView(new Image("/icons/next_stream.png")));
        prevStreamBtn.setGraphic(new ImageView(new Image("/icons/prev_stream.png")));
        packetInfo = new PacketInfo();
        parser = new PacketParser();
    }

    public boolean initStreamBuilder(String pcapFileBinary, List<Profile> profileList, int selectedProfileIndex, String yamlFileName, StreamBuilderType type) {
        selectedProfile = profileList.get(selectedProfileIndex);
        this.profileList = profileList;
        this.yamlFileName = yamlFileName;
        currentSelectedProfileIndex = selectedProfileIndex;
        
        if (selectedProfile.getStream().getAdvancedMode()
                && !ConnectionManager.getInstance().isScapyConnected()) {
            boolean loop = true;
            while (loop) {
                eventBus.post(new ScapyClientNeedConnectEvent());
                if (ConnectionManager.getInstance().isScapyConnected()) {
                    loop = false;
                }
                else {
                    loop = alertWarning("Can't open packet editor in Advanced mode",
                            "There is no connection to Scapy server."
                                    + "\nPlease refer to documentation about"
                                    + "\nScapy server and advanced mode.");
                }
            }
            if (!ConnectionManager.getInstance().isScapyConnected()) {
                return false;
            }
        }

        packetBuilderController.reset();
        streamPropertiesController.init(profileList, selectedProfileIndex);
        updateNextPrevButtonState();
        switch (type) {
            case BUILD_STREAM:
                initStreamBuilder(new BuilderDataBinding());
                showSimpleModeTabs();
                break;
            case EDIT_STREAM:
                initEditStream(pcapFileBinary);
                if(selectedProfile.getStream().getAdvancedMode()) {
                    showAdvancedModeTabs();
                } else {
                    showSimpleModeTabs();
                }
                break;
            default:
                break;
        }

        return true;
    }

    private void initEditStream(String pcapFileBinary) {
        streamTabPane.setDisable(false);
        saveButton.setDisable(false);
        streamEditorModeBtn.setDisable(false);
        Stream currentStream = selectedProfile.getStream();
        streamEditorModeBtn.setText(currentStream.getAdvancedMode() ? "Simple mode" : "Advanced mode");
        if (!Util.isNullOrEmpty(currentStream.getPacket().getMeta())) {
            BuilderDataBinding dataBinding = getDataBinding();
            if (dataBinding != null) {
                initStreamBuilder(dataBinding);
                return;
            } else {
                streamTabPane.setDisable(true);
                saveButton.setDisable(true);
                streamEditorModeBtn.setDisable(true);
            }
        } else {
            isImportedStreamProperty.set(true);
        }

        if (isImportedStreamProperty.get()) {
            streamTabPane.getTabs().remove(protocolDataTab);
            streamTabPane.getTabs().remove(protocolSelectionTab);
            streamTabPane.getTabs().remove(advanceSettingsTab);
            streamTabPane.getTabs().remove(packetViewerWithTreeTab);
            streamTabPane.getTabs().remove(packetEditorTab);
            streamTabPane.getTabs().remove(fieldEngineTab);
        }
        
        if (pcapFileBinary != null) {
            try {
                isBuildPacket = false;
                File pcapFile = trafficProfile.decodePcapBinary(pcapFileBinary);
                parser.parseFile(pcapFile.getAbsolutePath(), packetInfo);
                packetHex.setData(packetInfo);
            } catch (IOException ex) {
                LOG.error("Failed to load PCAP value", ex);
            }
        }
        String base64UserModel = currentStream.getPacket().getModel();
        if (!Strings.isNullOrEmpty(base64UserModel)) {
            packetBuilderController.loadUserModel(base64UserModel);
        }
    }
    
    private BuilderDataBinding getDataBinding() {
        String meta = selectedProfile.getStream().getPacket().getMeta();
        boolean emptyMeta = meta == null; 
        isImportedStreamProperty.setValue(emptyMeta);
        if (emptyMeta) {
            return null;
        }
        final String metaJSON = new String(Base64.getDecoder().decode(meta));
        if (metaJSON.charAt(0) != '{') {
            TrexAlertBuilder.build()
                    .setType(Alert.AlertType.ERROR)
                    .setTitle("Warning")
                    .setHeader("Stream initialization failed")
                    .setContent("This stream couldn't be edited due to outdated data format.")
                    .getAlert()
                    .showAndWait();
            return null;
        }
        try {
            return new ObjectMapper().readValue(metaJSON, BuilderDataBinding.class);
        } catch (Exception exc) {
            LOG.error("Can't read packet meta", exc);
            TrexAlertBuilder.build()
                    .setType(Alert.AlertType.ERROR)
                    .setTitle("Warning")
                    .setHeader("Stream initialization failed")
                    .setContent(exc.getMessage())
                    .getAlert()
                    .showAndWait();
            return null;
        }
    }

    private void initStreamBuilder(BuilderDataBinding builderDataBinder) {
        isImportedStreamProperty.setValue(false);
        isBuildPacket = true;
        String packetEditorModel = selectedProfile.getStream().getPacket().getModel();
        if (!Strings.isNullOrEmpty(packetEditorModel)) {
            packetBuilderController.loadUserModel(packetEditorModel);
        }
        this.builderDataBinder = builderDataBinder;
        // initialize builder tabs
        protocolSelectionController.bindSelections(builderDataBinder.getProtocolSelection());
        protocolDataController.bindSelection(builderDataBinder);
        advancedSettingsController.bindSelections(builderDataBinder.getAdvancedPropertiesDB());

        packetViewerWithTreeTab.selectedProperty().addListener((ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) -> {
            if (newValue) {
                try {
                    // handle it and send the result to packet viewer controller
                    packetViewerController.setPacketView(protocolDataController.getProtocolData());
                } catch (Exception ex) {
                    LOG.error("Error creating packet", ex);
                }
            }
        });
    }

    private void showSimpleModeTabs() {
        streamTabPane.getTabs().clear();
        if (isImportedStreamProperty.get()) {
            streamTabPane.getTabs().addAll(
                    streamPropertiesTab,
                    packetViewerTab
            );
        } else {
            streamTabPane.getTabs().addAll(
                    streamPropertiesTab,
                    protocolSelectionTab,
                    protocolDataTab,
                    advanceSettingsTab,
                    packetViewerWithTreeTab
            );
        }
    }

    private void showAdvancedModeTabs() {
        streamTabPane.getTabs().clear();
        streamTabPane.getTabs().addAll(streamPropertiesTab, packetEditorTab, fieldEngineTab);
    }

    @FXML
    public void handleCloseDialog(final MouseEvent event) {
        Node node = (Node) event.getSource();
        Stage stage = (Stage) node.getScene().getWindow();
        stage.hide();
    }

    @FXML
    public void saveProfileBtnClicked(ActionEvent event) {
        if (saveStream()) {
            // close the dialog
            Node node = (Node) event.getSource();
            Stage stage = (Stage) node.getScene().getWindow();
            stage.hide();
        }
    }

    private boolean saveStream() {
        try {
            String fieldEngineError = packetBuilderController.getFieldEngineError();
            if (!Strings.isNullOrEmpty(fieldEngineError))  {
                streamTabPane.getSelectionModel().select(fieldEngineTab);
                LOG.error("Unable to save stream due to errors in Field Engine:" + fieldEngineError);
                return false;
            }
            updateCurrentProfile();
            if (streamPropertiesController.isValidStreamPropertiesFields()) {
                String yamlData = trafficProfile.convertTrafficProfileToYaml(profileList.toArray(new Profile[profileList.size()]));
                FileUtils.writeStringToFile(new File(yamlFileName), yamlData);
                Util.optimizeMemory();
                return true;
            }
        } catch (Exception ex) {
            LOG.error("Error Saving yaml file", ex);
        }
        return false;
    }

    @FXML
    public void nextStreamBtnClicked(ActionEvent event) {
        nextStreamBtn.setDisable(true);
        loadProfile(true);
    }

    @FXML
    public void switchEditorMode(ActionEvent event) throws Exception {
        Stream currentStream = streamPropertiesController.getUpdatedSelectedProfile().getStream();
        boolean advancedMode = currentStream.getAdvancedMode();

        try {
            if (!ConnectionManager.getInstance().isScapyConnected()) {
                eventBus.post(new ScapyClientNeedConnectEvent());
            }
            if (advancedMode) {
                streamEditorModeBtn.setText("Advanced mode");
                currentStream.setAdvancedMode(false);
                showSimpleModeTabs();
            } else {
                if (ConnectionManager.getInstance().isScapyConnected()) {
                    if (isImportedStreamProperty.getValue()) {
                        byte[] base64Packet = currentStream.getPacket().getBinary().getBytes();
                        byte[] packet = Base64.getDecoder().decode(base64Packet);
                        packetBuilderController.loadPcapBinary(packet);
                    } else {
                        packetBuilderController.loadSimpleUserModel(builderDataBinder.serializeAsPacketModel());
                    }
                    streamEditorModeBtn.setText("Simple mode");
                    currentStream.setAdvancedMode(true);
                    showAdvancedModeTabs();
                } else {
                    alertWarning("Can't open Advanced mode", "There is no connection to Scapy server."
                            + "\nPlease refer to documentation about"
                            + "\nScapy server and advanced configuration mode.");
                }
            }
        } catch (Exception e) {
            LOG.error("Unable to open advanced mode due to: " + e.getMessage());
            alertWarning("Can't open Advanced mode", "Some errors occurred. See logs for more details.");
        }
    }

    @FXML
    public void prevStreamBtnClick(ActionEvent event) {
        prevStreamBtn.setDisable(true);
        loadProfile(false);
    }

    private void loadProfile(boolean isNext) {
        try {
            Util.optimizeMemory();
            updateCurrentProfile();
            if (streamPropertiesController.isValidStreamPropertiesFields()) {
                if (isNext) {
                    this.currentSelectedProfileIndex += 1;
                } else {
                    this.currentSelectedProfileIndex -= 1;
                }
                loadStream();
            }
        } catch (Exception ex) {
            LOG.error("Invalid data", ex);
        }
        updateNextPrevButtonState();
    }

    private void resetTabs() {
        streamTabPane.getTabs().clear();
        streamTabPane.getTabs().add(streamPropertiesTab);
        streamTabPane.getTabs().add(packetViewerTab);
        streamTabPane.getTabs().add(protocolSelectionTab);
        streamTabPane.getTabs().add(protocolDataTab);
        streamTabPane.getTabs().add(advanceSettingsTab);
        streamTabPane.getTabs().add(packetViewerWithTreeTab);
        streamTabPane.getTabs().add(packetEditorTab);
        streamTabPane.getTabs().add(fieldEngineTab);
    }

    private void updateNextPrevButtonState() {
        nextStreamBtn.setDisable((currentSelectedProfileIndex >= profileList.size() - 1));
        prevStreamBtn.setDisable((currentSelectedProfileIndex == 0));
    }

    private void loadStream() {
        resetTabs();
        streamTabPane.getSelectionModel().select(streamPropertiesTab);
        selectedProfile = profileList.get(currentSelectedProfileIndex);
        Stream currentStream = selectedProfile.getStream();
        String windowTitle = "Edit Stream (" + selectedProfile.getName() + ")";
        // update window title
        Stage stage = (Stage) streamTabPane.getScene().getWindow();
        stage.setTitle(windowTitle);

        streamPropertiesController.init(profileList, currentSelectedProfileIndex);
        initEditStream(currentStream.getPacket().getBinary());
        if (currentStream.getAdvancedMode()) {
            showAdvancedModeTabs();
        } else {
            showSimpleModeTabs();
        }
    }

    private void updateCurrentProfile() throws Exception {
        selectedProfile = streamPropertiesController.getUpdatedSelectedProfile();
        String hexPacket = null;
        if (packetHex != null && !isBuildPacket) {
            hexPacket = packetHex.getPacketHexFromList();
        } else if (isBuildPacket) {
            hexPacket = PacketBuilderHelper.getPacketHex(protocolDataController.getProtocolData().getPacket().getRawData());
            selectedProfile.getStream().setAdditionalProperties(protocolDataController.getVm(advancedSettingsController.getCacheSize()));
            selectedProfile.getStream().setFlags(protocolDataController.getFlagsValue());

            // save stream selected in stream property
            final String metaJSON = new ObjectMapper().writeValueAsString(builderDataBinder);
            final String encodedMeta = Base64.getEncoder().encodeToString(metaJSON.getBytes());
            selectedProfile.getStream().getPacket().setMeta(encodedMeta);
        }
        String encodedBinaryPacket = trafficProfile.encodeBinaryFromHexString(hexPacket);
        Packet packet = selectedProfile.getStream().getPacket();
        
        if (selectedProfile.getStream().getAdvancedMode()) {
            packet.setBinary(packetBuilderController.getModel().getPkt().binary);
            packet.setModel(packetBuilderController.getModel().serialize());
            selectedProfile.getStream().setAdditionalProperties(packetBuilderController.getPktVmInstructions());
        } else {
            packet.setBinary(encodedBinaryPacket);
            packet.setModel("");
        }
    }

    @Override
    public void onEnterKeyPressed(Stage stage) {
        // ignore event
    }

    @Override
    public void onEscapKeyPressed() {
        // ignoring global escape
    }

    private boolean alertWarning(String header, String content) {
        ButtonType buttonTypeTry = new ButtonType("Try", ButtonBar.ButtonData.YES);
        ButtonType buttonTypeCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = TrexAlertBuilder.build()
                .setType(Alert.AlertType.WARNING)
                .setTitle("Warning")
                .setHeader(header)
                .setContent(content + "\nWould you like to change ip or port and try connection again ?")
                .setButtons(buttonTypeTry, buttonTypeCancel)
                .getAlert();

        alert.showAndWait();
        ButtonType res = alert.getResult();
        return res.getButtonData() != ButtonBar.ButtonData.CANCEL_CLOSE;
    }
}
