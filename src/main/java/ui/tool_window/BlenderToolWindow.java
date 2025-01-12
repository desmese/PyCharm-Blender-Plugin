package ui.tool_window;

import data.*;
import util.core.MyFileUtils;
import util.core.MyIterator;
import util.core.json_util.MyJsonDocument;
import util.core.json_util.MyJsonParser;
import util.core.json_util.values.MyJsonObject;
import util.core.json_util.values.MyJsonString;
import util.core.socket.server.MyServerSocket;
import util.core.socket.MySocketConnection;
import util.MyInputStreamHelper;
import util.MyProjectHolder;
import util.MySwingUtil;
import plugin_settings.PluginSettings;
import ui.dialogs.add_blender_instance.AddBlenderInstanceWrapper;
import ui.dialogs.remove_blender_instance.RemoveBlenderInstanceWrapper;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.PathMappingSettings;
import com.intellij.util.messages.MessageBusConnection;
import com.jetbrains.python.console.PythonDebugLanguageConsoleView;
import com.jetbrains.python.debugger.remote.PyRemoteDebugConfiguration;
import com.jetbrains.python.debugger.remote.PyRemoteDebugConfigurationFactory;
import com.jetbrains.python.debugger.remote.PyRemoteDebugConfigurationType;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BlenderToolWindow {

    private static final String blenderRunFileName = "pycharm_connector.py";
    private static final File runningFile = new File(System.getProperty("java.io.tmpdir") + File.separator + "BlendCharm" + File.separator + blenderRunFileName);
    private static final File egg = BlenderToolWindowUtils.getEggFile();
    private static final int debugPort = 8132;
    private static final int socketPort = 8525;

    private final BlenderSettings blenderSettings;
    private final MyProjectHolder project;
    private final MyServerSocket currentSocket = new MyServerSocket("localhost", socketPort);

    private JPanel myToolWindowContent;
    private JComboBox<BlenderInstance> blenderInstances;

    private JLabel button_add;
    private JLabel button_remove;
    private JLabel start;
    private JLabel debug;
    private JList<RunningBlenderProcess> runningInstances;
    private JPanel consolePanel;
    private JPanel nullPanel;

    private static DefaultListModel<RunningBlenderProcess> runningInstancesAdapter;

    BlenderToolWindow(@NotNull Project project) {
        this.project = new MyProjectHolder(project);
        this.blenderSettings = BlenderSettings.getBlenderSettings(this.project);
        init();

        for (BlenderInstance savedBlenderInstance : blenderSettings.getBlenderInstances())
            blenderInstances.addItem(savedBlenderInstance);

        blenderInstances.addItemListener(this::onBlenderInstanceChange);
        MySwingUtil.setLabelOnClickListener(button_add, this::onAddClick);
        MySwingUtil.setLabelOnClickListener(button_remove, this::onRemoveClick);

        MySwingUtil.setLabelOnClickListener(start, this::onStartClick);
        MySwingUtil.setLabelOnClickListener(debug, this::onDebugClick);

        validateFile(true);
        updateButtons();

        runningInstancesAdapter = new DefaultListModel<>();
        runningInstances.setModel(runningInstancesAdapter);
        runningInstances.setCellRenderer(new RunningBlenderProcessRenderer());

        runningInstances.addListSelectionListener(e -> {
            int index = runningInstances.getSelectedIndex();
            RunningBlenderProcess process = index == -1 ? null : runningInstancesAdapter.get(runningInstances.getSelectedIndex());
            setConsoleView(process);
        });
    }

    private boolean isSelectedInstanceValid() {
        return blenderInstances.getModel().getSize() != 0;
    }

    private void init() {
        MessageBusConnection connection = project.getProject().getMessageBus().connect();
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) if (event.isFromSave()) onSave(event.getFile());
            }
        });
    }

    private void onSave(VirtualFile virtualFile) {
        String ofAddon = new VirtualBlenderFile(project, virtualFile).getRelativeAddonName();
        if (ofAddon != null) reloadAddons(new MyIterator<>(new String[]{ofAddon}));
    }

    private static void reloadAddons(MyIterator<String> strings) {
        for (int i = 0; i < runningInstancesAdapter.size(); i++) {
            MySocketConnection socket = runningInstancesAdapter.get(i).getSocket();
            if (socket == null) continue;
            socket.sendJsonData(new MyJsonDocument().getRoot()
                    .addKeyJsonString(CommunicationData.REQUEST, CommunicationData.REQUEST_PLUGIN_REFRESH)
                    .addKeyJsonArray(CommunicationData.REQUEST_PLUGIN_REFRESH_NAME_LIST, strings, MyJsonString::new)
            );
        }
    }

    private BlenderInstance getSelectedBlenderInstance() {
        return (BlenderInstance) blenderInstances.getSelectedItem();
    }

    private void updateButtons() {
        start.setEnabled(isSelectedInstanceValid());
        debug.setEnabled(isSelectedInstanceValid() && !PluginSettings.isCommunity);

        button_remove.setEnabled(getSelectedBlenderInstance() != null);
    }

    private void addConfiguration(BlenderInstance configuration) {
        blenderSettings.addBlenderInstance(configuration);
        blenderInstances.addItem(configuration);
    }

    private void onBlenderInstanceChange(ItemEvent itemEvent) {
        updateButtons();
    }

    JPanel getContent() {
        return myToolWindowContent;
    }

    /**
     * Validate that the runningFile variable could be used.
     *
     * @return true if the file exist or has been successfully created.
     */
    private boolean validateFile(boolean checkContent) {
        if (runningFile.exists() && !checkContent) return true;

        InputStream resourceAsStream = this.getClass().getClassLoader().getResourceAsStream("Python/pycharm_connector.py");
        if (resourceAsStream == null) return false;
        String realContent = MyInputStreamHelper.readString(resourceAsStream);

        if (runningFile.exists()) {
            if (realContent.equals(MyFileUtils.readText(runningFile.getPath()))) return true;
            if (!runningFile.delete()) return false;
        }
        File dirs = runningFile.getParentFile();

        if (dirs.exists() || dirs.mkdirs()) {
            try {
                if (runningFile.createNewFile()) {
                    OutputStream outStream = new FileOutputStream(runningFile);
                    outStream.write(realContent.getBytes());
                    return true;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /*
     *  Socket methods
     */

    private void onInstanceConnectionStart(MySocketConnection socket, RunningBlenderProcess runningBlenderProcess) {
        runningBlenderProcess.assignSocket(socket);
        blenderSettings.removeDeletedAddon(project);

        socket.sendJsonData(new MyJsonDocument().getRoot()
                .addKeyJsonString(CommunicationData.REQUEST, CommunicationData.REQUEST_PLUGIN_FOLDER)
                .addKeyJsonString(CommunicationData.REQUEST_PLUGIN_FOLDER_PROJECT_FOLDER, project.getBasePath())
                .addKeyJsonArray(CommunicationData.REQUEST_PLUGIN_FOLDER_ADDON_NAMES, blenderSettings.getBlenderAddons(), MyJsonString::new)
        );
    }

    private void onInstanceMessage(MySocketConnection.Data message, RunningBlenderProcess runningBlenderProcess) {
        MyJsonDocument document = new MyJsonParser().parse(message.getStringData());
        MyJsonObject<?> root = document.getRoot();
        switch (root.get(CommunicationData.RESPONSE).getIntValue()) {
            case CommunicationData.RESPONSE_PLUGIN_FOLDER:
                String addonPath = root.get(CommunicationData.RESPONSE_PLUGIN_FOLDER_PLUGIN_PATH).getStringValue();
                String currentPath = runningBlenderProcess.getInstance().addonPath;
                if (currentPath == null || !currentPath.equals(addonPath)) {
                    runningBlenderProcess.getInstance().addonPath = addonPath;
                    if (runningBlenderProcess.isDebug()) runningBlenderProcess.getProcess().destroyProcess();
                }
                break;
            case CommunicationData.RESPONSE_PLUGIN_REFRESH:
                root.get(CommunicationData.RESPONSE_PLUGIN_REFRESH_STATUS).getStringValue();
                break;
        }
    }

    private void onInstanceConnectionEnd(RunningBlenderProcess runningBlenderProcess) {
        runningBlenderProcess.getProcess().destroyProcess();
        ApplicationManager.getApplication().invokeLater(() -> runningInstancesAdapter.removeElement(runningBlenderProcess));
    }

    /*
     *  Process Starting
     */

    @NotNull
    private RunningBlenderProcess startBlenderProcess(boolean debugMode) throws ExecutionException {
        if (currentSocket.open()) {
            BlenderInstance instance = getSelectedBlenderInstance();
            ArrayList<String> command = new ArrayList<>();

            command.add(instance.path);
            command.add("--python");
            command.add(runningFile.getPath());
            command.add("--");
            if (debugMode && egg != null && egg.exists()) {
                command.add("debug_mode");
                command.add(".");
                command.add("debug_port");
                command.add(String.valueOf(debugPort));
                command.add("debug_egg");
                command.add(egg.getPath());
            }

            GeneralCommandLine generalCommandLine = new GeneralCommandLine(command);
            generalCommandLine.setCharset(StandardCharsets.UTF_8);
            generalCommandLine.setWorkDirectory(project.getBasePath());
            OSProcessHandler processHandler = new OSProcessHandler(generalCommandLine);
            RunningBlenderProcess runningBlenderProcess = new RunningBlenderProcess(instance, processHandler, debugMode);

            currentSocket.asyncWaitClient(new MySocketConnection.MySocketConnectionInterface() {
                @Override
                public void onConnectionStart(MySocketConnection socket) {
                    onInstanceConnectionStart(socket, runningBlenderProcess);
                }

                @Override
                public void onMessage(MySocketConnection socket, MySocketConnection.Data message) {
                    onInstanceMessage(message, runningBlenderProcess);
                }

                @Override
                public void onEnd(MySocketConnection socket) {
                    onInstanceConnectionEnd(runningBlenderProcess);
                }
            });

            runningInstancesAdapter.addElement(runningBlenderProcess);
            runningInstances.setSelectedIndex(runningInstancesAdapter.size() - 1);
            return runningBlenderProcess;
        }
        throw new ExecutionException("Socket not open!");
    }

    /*
     *  Label Listener
     */

    private void onAddClick() {
        AddBlenderInstanceWrapper dialog = new AddBlenderInstanceWrapper(project.getProject());
        if (dialog.showAndGet()) addConfiguration(dialog.form.getConfiguration());
    }

    private void onRemoveClick() {
        if (getSelectedBlenderInstance() == null) return;

        RemoveBlenderInstanceWrapper dialog = new RemoveBlenderInstanceWrapper(project.getProject());
        if (dialog.showAndGet()) {
            blenderSettings.removeBlenderInstances(getSelectedBlenderInstance());
            blenderInstances.removeItem(getSelectedBlenderInstance());
            updateButtons();
        }
    }

    private void onStartClick() {
        if (validateFile(false)) {
            try {
                ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project.getProject()).getConsole();

                RunningBlenderProcess runningBlenderProcess = startBlenderProcess(false);
                runningBlenderProcess.setComponent(console.getComponent());

                setConsoleView(runningBlenderProcess);

                console.attachToProcess(runningBlenderProcess.getProcess());
                runningBlenderProcess.getProcess().startNotify();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    private void setConsoleView(RunningBlenderProcess runningBlenderProcess) {
        consolePanel.removeAll();
        consolePanel.revalidate();
        consolePanel.repaint();
        consolePanel.add(runningBlenderProcess != null ? runningBlenderProcess.getComponent() : nullPanel, BorderLayout.CENTER);
    }

    private void onDebugClick() {
        if (PluginSettings.isCommunity) return;

        if (validateFile(false)) {
            try {
                PyRemoteDebugConfigurationType remoteConfigurationType = PyRemoteDebugConfigurationType.getInstance();

                PyRemoteDebugConfigurationFactory factory = (PyRemoteDebugConfigurationFactory) remoteConfigurationType.getConfigurationFactories()[0];

                RunnerAndConfigurationSettings runSettings = RunManager.getInstance(project.getProject()).createConfiguration("Blender Debug", factory);
                PyRemoteDebugConfiguration configuration = (PyRemoteDebugConfiguration) runSettings.getConfiguration();

                configuration.setHost("localhost");
                configuration.setPort(debugPort);
                configuration.setSuspendAfterConnect(false);

                configuration.setMappingSettings(new PathMappingSettings() {{
                    add(new PathMapping(project.getBasePath(), getSelectedBlenderInstance().addonPath));
                }});

                ProgramRunner.Callback callback = runContentDescriptor -> {
                    try {
                        RunningBlenderProcess processHandler = startBlenderProcess(true);
                        ((PythonDebugLanguageConsoleView) runContentDescriptor.getExecutionConsole()).attachToProcess(processHandler.getProcess());
                        processHandler.getProcess().startNotify();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }
                };

                Executor debugExecutorInstance = DefaultDebugExecutor.getDebugExecutorInstance();
                ExecutionEnvironment executionEnvironment = ExecutionEnvironmentBuilder.create(project.getProject(), debugExecutorInstance, configuration).build();
                executionEnvironment.getRunner().execute(executionEnvironment, callback);
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
    }
}
