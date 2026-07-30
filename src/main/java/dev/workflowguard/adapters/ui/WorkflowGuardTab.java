package dev.workflowguard.adapters.ui;

import dev.workflowguard.application.ExecutionCoordinator;
import dev.workflowguard.application.JsonEvidenceExporter;
import dev.workflowguard.application.WorkflowArchiveService;
import dev.workflowguard.application.WorkflowService;
import dev.workflowguard.application.WorkflowSnapshot;
import dev.workflowguard.core.ExecutionRunSummarizer;
import dev.workflowguard.core.HttpRequestText;
import dev.workflowguard.core.SafetyGate;
import dev.workflowguard.core.WorkflowDependencySuggester;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.DependencyConfidence;
import dev.workflowguard.domain.ExecutionPlan;
import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.ExecutionRunSummary;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.InvariantCheckResult;
import dev.workflowguard.domain.InvariantRule;
import dev.workflowguard.domain.JsonInvariant;
import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.ProbeInvariant;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepExecutionResult;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowDependency;
import dev.workflowguard.domain.WorkflowStep;
import dev.workflowguard.ports.AuditIssuePublisher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class WorkflowGuardTab extends JPanel implements AutoCloseable {
    private static final int MAXIMUM_RUN_HISTORY = 50;

    private final WorkflowService workflowService;
    private final ExecutionCoordinator executionCoordinator;
    private final AuditIssuePublisher auditIssuePublisher;
    private final boolean burpIssueViewerAvailable;
    private final Consumer<Throwable> errorReporter;
    private final Component dialogParent;
    private final DefaultListModel<Workflow> workflowListModel = new DefaultListModel<>();
    private final JList<Workflow> workflowList = new JList<>(workflowListModel);
    private final StepTableModel stepTableModel = new StepTableModel();
    private final JTable stepTable = new JTable(stepTableModel);
    private final MutationTableModel mutationTableModel = new MutationTableModel();
    private final JTable mutationTable = new JTable(mutationTableModel);
    private final RunResultTableModel runResultTableModel = new RunResultTableModel();
    private final JTable runResultTable = new JTable(runResultTableModel);
    private final ResultMatrixTableModel resultMatrixTableModel = new ResultMatrixTableModel();
    private final JTable resultMatrixTable = new JTable(resultMatrixTableModel);
    private final VariableTableModel variableTableModel = new VariableTableModel();
    private final JTable variableTable = new JTable(variableTableModel);
    private final InvariantTableModel invariantTableModel = new InvariantTableModel();
    private final JTable invariantTable = new JTable(invariantTableModel);
    private final ActorTableModel actorTableModel = new ActorTableModel();
    private final JTable actorTable = new JTable(actorTableModel);
    private final WorkflowDependencyTableModel dependencyTableModel =
            new WorkflowDependencyTableModel();
    private final JTable dependencyTable = new JTable(dependencyTableModel);
    private final WorkflowDependencySuggester dependencySuggester =
            new WorkflowDependencySuggester();
    private final SafetyGate safetyGate = new SafetyGate();
    private final WorkflowGraphPanel workflowGraphPanel;
    private final JLabel dependencySummary = new JLabel("No dependency suggestions.");
    private final InvariantResultTableModel invariantResultTableModel =
            new InvariantResultTableModel();
    private final JTable invariantResultTable = new JTable(invariantResultTableModel);
    private final JTextArea rawRequest = new JTextArea();
    private final JTextArea executionEvidence = new JTextArea();
    private final JComboBox<StepCategory> categoryEditor = new JComboBox<>(StepCategory.values());
    private final JComboBox<StepRole> roleEditor = new JComboBox<>(StepRole.values());
    private final JComboBox<ActorDefinition> actorEditor = new JComboBox<>();
    private final JCheckBox enabledEditor = new JCheckBox("Enabled");
    private final JCheckBox skipMutation = new JCheckBox("Skip", true);
    private final JCheckBox repeatMutation = new JCheckBox("Repeat", true);
    private final JCheckBox replayMutation = new JCheckBox("Replay earlier", true);
    private final JCheckBox swapActorMutation = new JCheckBox("Swap actor", true);
    private final JCheckBox staleVariableMutation = new JCheckBox("Stale value", true);
    private final JCheckBox stateAwarePresets = new JCheckBox("State-aware presets", true);
    private final JSpinner maximumCases = new JSpinner(new SpinnerNumberModel(20, 1, 200, 1));
    private final JSpinner maximumRequests = new JSpinner(new SpinnerNumberModel(
            20,
            1,
            ExecutionPolicy.ABSOLUTE_MAXIMUM_REQUESTS,
            1
    ));
    private final JSpinner delayMilliseconds = new JSpinner(
            new SpinnerNumberModel(1000, 0, 60_000, 100)
    );
    private final JCheckBox inScopeOnly = new JCheckBox("In-scope only", true);
    private final JCheckBox publishBurpIssues =
            new JCheckBox("Publish invariant failures to Burp issues", true);
    private final JButton runSelected = new JButton("Run selected case");
    private final JButton exportEvidence = new JButton("Export redacted JSON");
    private final JLabel runStatus = new JLabel("No run yet.");
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JsonEvidenceExporter evidenceExporter = new JsonEvidenceExporter();
    private final WorkflowArchiveService workflowArchiveService = new WorkflowArchiveService();
    private final List<ExecutionRun> runHistory = new ArrayList<>();
    private final AutoCloseable changeRegistration;

    private boolean refreshing;
    private ExecutionRun lastRun;

    public WorkflowGuardTab(
            WorkflowService workflowService,
            ExecutionCoordinator executionCoordinator
    ) {
        this(workflowService, executionCoordinator, AuditIssuePublisher.disabled());
    }

    public WorkflowGuardTab(
            WorkflowService workflowService,
            ExecutionCoordinator executionCoordinator,
            AuditIssuePublisher auditIssuePublisher
    ) {
        this(
                workflowService,
                executionCoordinator,
                auditIssuePublisher,
                true,
                ignored -> {
                }
        );
    }

    public WorkflowGuardTab(
            WorkflowService workflowService,
            ExecutionCoordinator executionCoordinator,
            AuditIssuePublisher auditIssuePublisher,
            boolean burpIssueViewerAvailable
    ) {
        this(
                workflowService,
                executionCoordinator,
                auditIssuePublisher,
                burpIssueViewerAvailable,
                ignored -> {
                }
        );
    }

    public WorkflowGuardTab(
            WorkflowService workflowService,
            ExecutionCoordinator executionCoordinator,
            AuditIssuePublisher auditIssuePublisher,
            boolean burpIssueViewerAvailable,
            Consumer<Throwable> errorReporter
    ) {
        this(
                workflowService,
                executionCoordinator,
                auditIssuePublisher,
                burpIssueViewerAvailable,
                errorReporter,
                null
        );
    }

    public WorkflowGuardTab(
            WorkflowService workflowService,
            ExecutionCoordinator executionCoordinator,
            AuditIssuePublisher auditIssuePublisher,
            boolean burpIssueViewerAvailable,
            Consumer<Throwable> errorReporter,
            Component dialogParent
    ) {
        super(new BorderLayout(8, 8));
        this.workflowService = Objects.requireNonNull(workflowService, "workflowService");
        this.executionCoordinator = Objects.requireNonNull(
                executionCoordinator,
                "executionCoordinator"
        );
        this.auditIssuePublisher = Objects.requireNonNull(
                auditIssuePublisher,
                "auditIssuePublisher"
        );
        this.burpIssueViewerAvailable = burpIssueViewerAvailable;
        this.errorReporter = Objects.requireNonNull(errorReporter, "errorReporter");
        this.dialogParent = dialogParent == null ? this : dialogParent;
        if (!burpIssueViewerAvailable) {
            publishBurpIssues.setText(
                    "Publish invariant failures to Burp issues (Pro viewer only)"
            );
            publishBurpIssues.setToolTipText(
                    "Burp Community accepts issue publication, but its All issues panel "
                            + "requires Burp Professional. Run evidence remains available here."
            );
        }
        workflowGraphPanel = new WorkflowGraphPanel(this::selectStepInTable);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildContent(), BorderLayout.CENTER);
        add(buildStatus(), BorderLayout.SOUTH);

        configureWorkflowList();
        configureStepTable();
        configureActorEditor();
        configureExecutionTable();
        changeRegistration = workflowService.onChange(() -> SwingUtilities.invokeLater(() -> {
            mutationTableModel.setCases(List.of());
            refresh();
        }));
        refresh();
    }

    private JComponent buildHeader() {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("WorkflowGuard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3f));
        container.add(title);
        container.add(Box.createVerticalStrut(3));
        container.add(new JLabel(
                "Capture, classify, and mutate a legitimate workflow while keeping probes and cleanup separate."
        ));
        container.add(Box.createVerticalStrut(8));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton newWorkflow = new JButton("New");
        newWorkflow.addActionListener(ignored -> createWorkflow());
        JButton renameWorkflow = new JButton("Rename");
        renameWorkflow.addActionListener(ignored -> renameWorkflow());
        JButton deleteWorkflow = new JButton("Delete");
        deleteWorkflow.addActionListener(ignored -> deleteWorkflow());
        JButton importWorkflow = new JButton("Import");
        importWorkflow.addActionListener(ignored -> importWorkflow());
        JButton exportWorkflow = new JButton("Export");
        exportWorkflow.addActionListener(ignored -> exportWorkflow());
        JButton generate = new JButton("Generate mutations");
        generate.addActionListener(ignored -> generateMutations());

        controls.add(newWorkflow);
        controls.add(renameWorkflow);
        controls.add(deleteWorkflow);
        controls.add(importWorkflow);
        controls.add(exportWorkflow);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(generate);
        controls.add(new JLabel("Types:"));
        controls.add(skipMutation);
        controls.add(repeatMutation);
        controls.add(replayMutation);
        controls.add(swapActorMutation);
        controls.add(staleVariableMutation);
        controls.add(stateAwarePresets);
        controls.add(new JLabel("Max cases:"));
        controls.add(maximumCases);
        container.add(controls);

        JPanel executionControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        runSelected.addActionListener(ignored -> runSelectedMutation());
        executionControls.add(runSelected);
        executionControls.add(new JLabel("Request cap:"));
        executionControls.add(maximumRequests);
        executionControls.add(new JLabel("Delay (ms):"));
        executionControls.add(delayMilliseconds);
        executionControls.add(inScopeOnly);
        executionControls.add(publishBurpIssues);
        executionControls.add(new JLabel(
                "State-changing cases always require an explicit confirmation."
        ));
        container.add(Box.createVerticalStrut(5));
        container.add(executionControls);
        return container;
    }

    private JComponent buildContent() {
        workflowList.setPreferredSize(new Dimension(240, 450));
        JScrollPane workflowsPane = new JScrollPane(workflowList);
        workflowsPane.setBorder(BorderFactory.createTitledBorder("Workflows"));

        mutationTable.setFillsViewportHeight(true);
        mutationTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane mutationsPane = new JScrollPane(mutationTable);
        mutationsPane.setBorder(BorderFactory.createEmptyBorder());

        JTabbedPane lowerTabs = new JTabbedPane();
        lowerTabs.addTab("Generated cases", mutationsPane);
        lowerTabs.addTab("Result matrix", buildResultMatrix());
        lowerTabs.addTab("Last run evidence", buildExecutionEvidence());

        JTabbedPane editors = new JTabbedPane();
        editors.addTab("Captured steps", buildStepEditor());
        editors.addTab("Actors", buildActorEditor());
        editors.addTab("Variables", buildVariableEditor());
        editors.addTab("Invariants", buildInvariantEditor());
        editors.addTab("Workflow graph", buildWorkflowGraph());

        JSplitPane vertical = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                editors,
                lowerTabs
        );
        vertical.setResizeWeight(0.68);

        JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workflowsPane, vertical);
        horizontal.setResizeWeight(0.18);
        return horizontal;
    }

    private JComponent buildStepEditor() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Captured steps"));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton moveUp = new JButton("Up");
        moveUp.addActionListener(ignored -> moveSelectedStep(-1));
        JButton moveDown = new JButton("Down");
        moveDown.addActionListener(ignored -> moveSelectedStep(1));
        JButton remove = new JButton("Remove");
        remove.addActionListener(ignored -> removeSelectedStep());
        JButton apply = new JButton("Apply metadata");
        apply.addActionListener(ignored -> applyStepMetadata());
        JButton saveTemplate = new JButton("Save request template");
        saveTemplate.addActionListener(ignored -> saveRequestTemplate());

        controls.add(moveUp);
        controls.add(moveDown);
        controls.add(remove);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(new JLabel("Category:"));
        controls.add(categoryEditor);
        controls.add(new JLabel("Role:"));
        controls.add(roleEditor);
        controls.add(new JLabel("Actor:"));
        controls.add(actorEditor);
        controls.add(enabledEditor);
        controls.add(apply);
        controls.add(saveTemplate);
        panel.add(controls, BorderLayout.NORTH);

        stepTable.setFillsViewportHeight(true);
        stepTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tablePane = new JScrollPane(stepTable);

        rawRequest.setEditable(true);
        rawRequest.setFont(new Font(Font.MONOSPACED, Font.PLAIN, rawRequest.getFont().getSize()));
        rawRequest.setLineWrap(false);
        JScrollPane rawPane = new JScrollPane(rawRequest);
        rawPane.setBorder(BorderFactory.createTitledBorder(
                "Request template — use ${variableName} placeholders"
        ));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePane, rawPane);
        split.setResizeWeight(0.62);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildActorEditor() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton add = new JButton("Add actor");
        add.addActionListener(ignored -> addActor());
        JButton edit = new JButton("Edit actor");
        edit.addActionListener(ignored -> editSelectedActor());
        JButton remove = new JButton("Remove actor");
        remove.addActionListener(ignored -> removeSelectedActor());
        controls.add(add);
        controls.add(edit);
        controls.add(remove);
        controls.add(new JLabel(
                "Each actor has an isolated per-origin cookie jar during every run."
        ));
        panel.add(controls, BorderLayout.NORTH);

        actorTable.setFillsViewportHeight(true);
        actorTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(actorTable), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildVariableEditor() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton add = new JButton("Add variable");
        add.addActionListener(ignored -> addVariable());
        JButton edit = new JButton("Edit variable");
        edit.addActionListener(ignored -> editSelectedVariable());
        JButton remove = new JButton("Remove variable");
        remove.addActionListener(ignored -> removeSelectedVariable());
        controls.add(add);
        controls.add(edit);
        controls.add(remove);
        controls.add(new JLabel(
                "Extract from a response, then reference the value as ${name} in later templates."
        ));
        panel.add(controls, BorderLayout.NORTH);

        variableTable.setFillsViewportHeight(true);
        variableTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(variableTable), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildInvariantEditor() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton add = new JButton("Add invariant");
        add.addActionListener(ignored -> addInvariant());
        JButton remove = new JButton("Remove invariant");
        remove.addActionListener(ignored -> removeSelectedInvariant());
        JButton volatilePaths = new JButton("Volatile JSON paths");
        volatilePaths.addActionListener(ignored -> editVolatileJsonPointers());
        controls.add(add);
        controls.add(remove);
        controls.add(volatilePaths);
        controls.add(new JLabel(
                "Expressions: after:/count >= before:/count && unchanged(/owner)."
        ));
        panel.add(controls, BorderLayout.NORTH);

        invariantTable.setFillsViewportHeight(true);
        invariantTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(invariantTable), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildWorkflowGraph() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel header = new JPanel(new BorderLayout());
        header.add(dependencySummary, BorderLayout.WEST);
        header.add(
                new JLabel("Click a node to select its captured step."),
                BorderLayout.EAST
        );
        panel.add(header, BorderLayout.NORTH);

        JScrollPane graphScroll = new JScrollPane(workflowGraphPanel);
        graphScroll.setBorder(BorderFactory.createEmptyBorder());
        graphScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        graphScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        dependencyTable.setFillsViewportHeight(true);
        dependencyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dependencyTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int row = dependencyTable.getSelectedRow();
            if (row >= 0) {
                WorkflowDependency dependency = dependencyTableModel.dependencyAt(
                        dependencyTable.convertRowIndexToModel(row)
                );
                selectStepInTable(dependency.targetStepId());
            }
        });
        JScrollPane suggestions = new JScrollPane(dependencyTable);
        suggestions.setBorder(BorderFactory.createTitledBorder(
                "Suggested dependencies and evidence"
        ));

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                graphScroll,
                suggestions
        );
        split.setResizeWeight(0.76);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildResultMatrix() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.add(
                new JLabel(
                        "Up to 50 summaries; full HTTP evidence is retained only for the latest run."
                ),
                BorderLayout.CENTER
        );
        JButton clear = new JButton("Clear matrix");
        clear.addActionListener(ignored -> clearResultMatrix());
        controls.add(clear, BorderLayout.EAST);
        panel.add(controls, BorderLayout.NORTH);

        resultMatrixTable.setFillsViewportHeight(true);
        resultMatrixTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(resultMatrixTable), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildExecutionEvidence() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        JPanel evidenceHeader = new JPanel(new BorderLayout(8, 0));
        evidenceHeader.add(runStatus, BorderLayout.CENTER);
        exportEvidence.setEnabled(false);
        exportEvidence.setToolTipText(
                "Exports the latest run while redacting credentials, tokens, cookies, and common personal-data fields."
        );
        exportEvidence.addActionListener(ignored -> exportLastRun());
        evidenceHeader.add(exportEvidence, BorderLayout.EAST);
        panel.add(evidenceHeader, BorderLayout.NORTH);

        runResultTable.setFillsViewportHeight(true);
        runResultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane resultsPane = new JScrollPane(runResultTable);

        executionEvidence.setEditable(false);
        executionEvidence.setFont(new Font(
                Font.MONOSPACED,
                Font.PLAIN,
                executionEvidence.getFont().getSize()
        ));
        executionEvidence.setLineWrap(false);
        JScrollPane evidencePane = new JScrollPane(executionEvidence);
        evidencePane.setBorder(BorderFactory.createTitledBorder("Request / response evidence"));

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                resultsPane,
                evidencePane
        );
        split.setResizeWeight(0.45);

        invariantResultTable.setFillsViewportHeight(true);
        invariantResultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTabbedPane evidenceTabs = new JTabbedPane();
        evidenceTabs.addTab("HTTP exchanges", split);
        evidenceTabs.addTab("Invariant checks", new JScrollPane(invariantResultTable));
        panel.add(evidenceTabs, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildStatus() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        panel.add(statusLabel, BorderLayout.WEST);
        panel.add(
                new JLabel("Capture: right-click requests → WorkflowGuard → Add to active workflow"),
                BorderLayout.EAST
        );
        return panel;
    }

    private void configureWorkflowList() {
        workflowList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        workflowList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Workflow workflow) {
                    long enabledActions = workflow.steps().stream()
                            .filter(WorkflowStep::enabled)
                            .filter(step -> step.role() == StepRole.ACTION)
                            .count();
                    setText(workflow.name() + "  (" + enabledActions + " actions)");
                }
                return this;
            }
        });
        workflowList.addListSelectionListener(event -> {
            if (refreshing || event.getValueIsAdjusting()) {
                return;
            }
            Workflow selected = workflowList.getSelectedValue();
            if (selected != null) {
                workflowService.selectWorkflow(selected.id());
                mutationTableModel.setCases(List.of());
            }
        });
    }

    private void configureStepTable() {
        stepTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateSelectedStepView();
                WorkflowStep selected = selectedStep();
                workflowGraphPanel.setSelectedStep(
                        selected == null ? null : selected.id()
                );
            }
        });
    }

    private void configureActorEditor() {
        actorEditor.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ActorDefinition actor) {
                    setText(actor.name());
                }
                return this;
            }
        });
    }

    private void configureExecutionTable() {
        runResultTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateExecutionEvidence();
            }
        });
        resultMatrixTable.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int selectedRow = resultMatrixTable.getSelectedRow();
            if (selectedRow >= 0) {
                showRun(resultMatrixTableModel.runAt(
                        resultMatrixTable.convertRowIndexToModel(selectedRow)
                ));
            }
        });
    }

    private void createWorkflow() {
        String name = JOptionPane.showInputDialog(
                dialogParent(),
                "Workflow name:",
                "New WorkflowGuard workflow",
                JOptionPane.PLAIN_MESSAGE
        );
        if (name == null || name.isBlank()) {
            return;
        }
        Workflow workflow = workflowService.createWorkflow(name.trim());
        statusLabel.setText("Created workflow '" + workflow.name() + "'.");
        mutationTableModel.setCases(List.of());
    }

    private void renameWorkflow() {
        Workflow workflow = workflowService.activeWorkflow().orElse(null);
        if (workflow == null) {
            return;
        }
        String name = (String) JOptionPane.showInputDialog(
                dialogParent(),
                "Workflow name:",
                "Rename WorkflowGuard workflow",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                workflow.name()
        );
        if (name == null || name.isBlank()) {
            return;
        }
        Workflow renamed = workflowService.renameActiveWorkflow(name.trim());
        statusLabel.setText("Renamed workflow to '" + renamed.name() + "'.");
    }

    private void deleteWorkflow() {
        Workflow workflow = workflowService.activeWorkflow().orElse(null);
        if (workflow == null) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                "Delete workflow '" + workflow.name() + "'?",
                "Delete workflow",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        Workflow selected = workflowService.deleteActiveWorkflow();
        mutationTableModel.setCases(List.of());
        statusLabel.setText("Deleted workflow. Active workflow is now '" + selected.name() + "'.");
    }

    private void moveSelectedStep(int offset) {
        WorkflowStep step = selectedStep();
        if (step == null) {
            statusLabel.setText("Select a step before moving it.");
            return;
        }
        workflowService.moveStep(step.id(), offset);
        mutationTableModel.setCases(List.of());
        statusLabel.setText("Moved " + shortStepName(step) + ".");
    }

    private void removeSelectedStep() {
        WorkflowStep step = selectedStep();
        if (step == null) {
            statusLabel.setText("Select a step before removing it.");
            return;
        }
        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                "Remove step '" + step.name() + "'?",
                "Remove captured step",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        workflowService.removeStep(step.id());
        mutationTableModel.setCases(List.of());
        statusLabel.setText("Removed " + step.name() + ".");
    }

    private void applyStepMetadata() {
        WorkflowStep step = selectedStep();
        if (step == null) {
            statusLabel.setText("Select a step before editing its metadata.");
            return;
        }
        workflowService.updateStep(
                step.id(),
                (StepCategory) categoryEditor.getSelectedItem(),
                (StepRole) roleEditor.getSelectedItem(),
                Objects.requireNonNull(
                        (ActorDefinition) actorEditor.getSelectedItem(),
                        "actor"
                ).id(),
                enabledEditor.isSelected()
        );
        mutationTableModel.setCases(List.of());
        statusLabel.setText("Updated metadata for " + shortStepName(step) + ".");
    }

    private void saveRequestTemplate() {
        WorkflowStep step = selectedStep();
        if (step == null) {
            statusLabel.setText("Select a step before saving its request template.");
            return;
        }
        if (rawRequest.getText().isBlank()) {
            statusLabel.setText("A request template must not be blank.");
            return;
        }
        try {
            workflowService.updateStepTemplate(
                    step.id(),
                    HttpRequestText.normalizeLineEndings(rawRequest.getText())
            );
            mutationTableModel.setCases(List.of());
            statusLabel.setText("Saved request template for " + shortStepName(step) + ".");
        } catch (RuntimeException exception) {
            statusLabel.setText("Unsafe request template: " + exception.getMessage());
        }
    }

    private void addActor() {
        ActorForm form = actorForm(null);
        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                form.panel(),
                "Add isolated actor",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            workflowService.addActor(
                    form.name().getText().trim(),
                    form.initialCookie().getText().trim(),
                    form.initialAuthorization().getText().trim(),
                    form.seedFromCaptured().isSelected()
            );
            statusLabel.setText("Added actor '" + form.name().getText().trim() + "'.");
        } catch (RuntimeException exception) {
            showEditorError("Unable to add actor", exception);
        }
    }

    private void editSelectedActor() {
        ActorDefinition actor = selectedActor();
        if (actor == null) {
            statusLabel.setText("Select an actor before editing it.");
            return;
        }
        ActorForm form = actorForm(actor);
        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                form.panel(),
                "Edit isolated actor",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            workflowService.updateActor(
                    actor.id(),
                    form.name().getText().trim(),
                    form.initialCookie().getText().trim(),
                    form.initialAuthorization().getText().trim(),
                    form.seedFromCaptured().isSelected()
            );
            statusLabel.setText("Updated actor '" + form.name().getText().trim() + "'.");
        } catch (RuntimeException exception) {
            showEditorError("Unable to update actor", exception);
        }
    }

    private void removeSelectedActor() {
        ActorDefinition actor = selectedActor();
        if (actor == null) {
            statusLabel.setText("Select an actor before removing it.");
            return;
        }
        if (actor.id().equals(ActorDefinition.DEFAULT_ID)) {
            statusLabel.setText("The default actor cannot be removed.");
            return;
        }
        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                "Remove actor '" + actor.name()
                        + "'? Its steps will be reassigned to the default actor.",
                "Remove actor",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        workflowService.removeActor(actor.id());
        mutationTableModel.setCases(List.of());
        statusLabel.setText("Removed actor '" + actor.name() + "'.");
    }

    private ActorForm actorForm(ActorDefinition actor) {
        JTextField name = new JTextField(actor == null ? "" : actor.name());
        JTextField initialCookie = new JPasswordField(
                actor == null ? "" : actor.initialCookieHeader()
        );
        JTextField initialAuthorization = new JPasswordField(
                actor == null ? "" : actor.initialAuthorizationHeader()
        );
        JCheckBox seedFromCaptured = new JCheckBox(
                "Seed this actor from the captured request Cookie header",
                actor != null && actor.seedFromCapturedRequest()
        );
        JPanel panel = formPanel(
                "Name", name,
                "Initial Cookie header", initialCookie,
                "Initial Authorization header", initialAuthorization,
                "Captured cookie fallback", seedFromCaptured,
                "Storage", new JLabel(
                        "Configured credentials are stored in Burp project extension data."
                )
        );
        return new ActorForm(
                panel,
                name,
                initialCookie,
                initialAuthorization,
                seedFromCaptured
        );
    }

    private ActorDefinition selectedActor() {
        int row = actorTable.getSelectedRow();
        if (row < 0 || row >= actorTableModel.getRowCount()) {
            return null;
        }
        return actorTableModel.actorAt(actorTable.convertRowIndexToModel(row));
    }

    private void addVariable() {
        showVariableEditor(null);
    }

    private void editSelectedVariable() {
        int row = variableTable.getSelectedRow();
        if (row < 0) {
            statusLabel.setText("Select a variable before editing it.");
            return;
        }
        showVariableEditor(variableTableModel.variableAt(
                variableTable.convertRowIndexToModel(row)
        ));
    }

    private void showVariableEditor(VariableDefinition existing) {
        Workflow workflow = workflowService.activeWorkflow().orElse(null);
        if (workflow == null || workflow.steps().isEmpty()) {
            statusLabel.setText("Capture at least one step before adding a variable.");
            return;
        }

        JTextField name = new JTextField(existing == null ? "" : existing.name());
        JComboBox<WorkflowStep> source = stepCombo(workflow.steps());
        if (existing != null) {
            selectStep(source, existing.sourceStepId());
        }
        JComboBox<ExtractionType> type = new JComboBox<>(ExtractionType.values());
        type.setSelectedItem(existing == null ? ExtractionType.REGEX : existing.extractionType());
        JTextField expression = new JTextField(
                existing == null ? "" : existing.expression()
        );
        JSpinner captureGroup = new JSpinner(new SpinnerNumberModel(
                existing == null ? 1 : existing.captureGroup(),
                0,
                50,
                1
        ));
        JTextField staleValue = new JTextField(
                existing == null ? "" : existing.staleValue()
        );
        JPanel form = formPanel(
                "Name", name,
                "Source step", source,
                "Extraction", type,
                "Regex / JSON Pointer", expression,
                "Regex capture group", captureGroup,
                "Stale object/token value (optional)", staleValue
        );

        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                form,
                existing == null ? "Add dynamic variable" : "Edit dynamic variable",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            WorkflowStep sourceStep = (WorkflowStep) source.getSelectedItem();
            VariableDefinition definition = new VariableDefinition(
                    name.getText().trim(),
                    Objects.requireNonNull(sourceStep, "sourceStep").id(),
                    (ExtractionType) type.getSelectedItem(),
                    expression.getText().trim(),
                    (Integer) captureGroup.getValue(),
                    staleValue.getText()
            );
            if (existing == null) {
                workflowService.addVariable(definition);
            } else {
                workflowService.updateVariable(existing.name(), definition);
            }
            statusLabel.setText(
                    (existing == null ? "Added" : "Updated")
                            + " variable '${" + name.getText().trim() + "}'."
            );
        } catch (RuntimeException exception) {
            showEditorError("Unable to save variable", exception);
        }
    }

    private void selectStep(JComboBox<WorkflowStep> combo, UUID stepId) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).id().equals(stepId)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
    }

    private void removeSelectedVariable() {
        int row = variableTable.getSelectedRow();
        if (row < 0) {
            statusLabel.setText("Select a variable before removing it.");
            return;
        }
        VariableDefinition variable = variableTableModel.variableAt(
                variableTable.convertRowIndexToModel(row)
        );
        workflowService.removeVariable(variable.name());
        statusLabel.setText("Removed variable '${" + variable.name() + "}'.");
    }

    private void addInvariant() {
        Workflow workflow = workflowService.activeWorkflow().orElse(null);
        if (workflow == null) {
            return;
        }
        List<WorkflowStep> probes = workflow.steps().stream()
                .filter(step -> step.role() == StepRole.PROBE)
                .toList();
        if (probes.isEmpty()) {
            statusLabel.setText("Assign the PROBE role to a step before adding an invariant.");
            return;
        }

        JComboBox<WorkflowStep> source = stepCombo(probes);
        JTextField name = new JTextField();
        JTextField pointer = new JTextField();
        JComboBox<InvariantRule> rule = new JComboBox<>(InvariantRule.values());
        JTextField expected = new JTextField();
        JPanel form = formPanel(
                "Name", name,
                "Probe step", source,
                "JSON Pointer (blank = root)", pointer,
                "Rule", rule,
                "Expected JSON / expression", expected
        );

        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                form,
                "Add probe invariant",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            WorkflowStep sourceStep = (WorkflowStep) source.getSelectedItem();
            String expectedJson = expected.getText().isBlank()
                    ? null
                    : expected.getText().trim();
            workflowService.addInvariant(
                    Objects.requireNonNull(sourceStep, "sourceStep").id(),
                    new JsonInvariant(
                            name.getText().trim(),
                            pointer.getText().trim(),
                            (InvariantRule) rule.getSelectedItem(),
                            expectedJson
                    )
            );
            statusLabel.setText("Added invariant '" + name.getText().trim() + "'.");
        } catch (RuntimeException exception) {
            showEditorError("Unable to add invariant", exception);
        }
    }

    private void removeSelectedInvariant() {
        int row = invariantTable.getSelectedRow();
        if (row < 0) {
            statusLabel.setText("Select an invariant before removing it.");
            return;
        }
        ProbeInvariant invariant = invariantTableModel.invariantAt(
                invariantTable.convertRowIndexToModel(row)
        );
        workflowService.removeInvariant(invariant.id());
        statusLabel.setText("Removed invariant '" + invariant.invariant().name() + "'.");
    }

    private void editVolatileJsonPointers() {
        Workflow workflow = workflowService.activeWorkflow().orElse(null);
        if (workflow == null) {
            return;
        }
        JTextArea pointers = new JTextArea(
                String.join(System.lineSeparator(), workflow.volatileJsonPointers()),
                9,
                52
        );
        pointers.setFont(new Font(Font.MONOSPACED, Font.PLAIN, pointers.getFont().getSize()));
        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                new Object[]{
                        "One JSON Pointer per line. These paths are ignored by UNCHANGED rules:",
                        new JScrollPane(pointers)
                },
                "Configure volatile JSON paths",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Set<String> configured = pointers.getText().lines()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            workflowService.updateVolatileJsonPointers(configured);
            statusLabel.setText(
                    "Configured " + configured.size() + " volatile JSON path(s)."
            );
        } catch (RuntimeException exception) {
            showEditorError("Unable to configure volatile JSON paths", exception);
        }
    }

    private JComboBox<WorkflowStep> stepCombo(List<WorkflowStep> steps) {
        JComboBox<WorkflowStep> combo = new JComboBox<>(steps.toArray(WorkflowStep[]::new));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof WorkflowStep step) {
                    setText(step.name() + " [" + step.role() + "]");
                }
                return this;
            }
        });
        return combo;
    }

    private JPanel formPanel(Object... labelAndFields) {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
        for (int index = 0; index < labelAndFields.length; index += 2) {
            panel.add(new JLabel(labelAndFields[index] + ":"));
            panel.add((Component) labelAndFields[index + 1]);
        }
        return panel;
    }

    private void showEditorError(String title, RuntimeException exception) {
        statusLabel.setText(title + ": " + exception.getMessage());
        JOptionPane.showMessageDialog(
                dialogParent(),
                exception.getMessage(),
                title,
                JOptionPane.ERROR_MESSAGE
        );
    }

    private void generateMutations() {
        EnumSet<MutationType> enabled = EnumSet.noneOf(MutationType.class);
        if (skipMutation.isSelected()) {
            enabled.add(MutationType.SKIP_STEP);
        }
        if (repeatMutation.isSelected()) {
            enabled.add(MutationType.REPEAT_STEP);
        }
        if (replayMutation.isSelected()) {
            enabled.add(MutationType.REPLAY_EARLIER_STEP);
        }
        if (swapActorMutation.isSelected()) {
            enabled.add(MutationType.SWAP_ACTOR);
        }
        if (staleVariableMutation.isSelected()) {
            enabled.add(MutationType.STALE_VARIABLE);
        }
        if (stateAwarePresets.isSelected()) {
            enabled.add(MutationType.REPLAY_AFTER_REVOKE);
            enabled.add(MutationType.REPLAY_AFTER_DELETE);
            enabled.add(MutationType.REPLAY_ONE_TIME_USE);
        }

        try {
            List<MutationCase> cases = workflowService.generateMutations(
                    enabled,
                    (Integer) maximumCases.getValue()
            );
            mutationTableModel.setCases(cases);
            if (!cases.isEmpty()) {
                mutationTable.setRowSelectionInterval(0, 0);
            }
            statusLabel.setText(
                    "Generated " + cases.size() + " preview case(s). No requests were sent."
            );
        } catch (RuntimeException exception) {
            mutationTableModel.setCases(List.of());
            statusLabel.setText("Unable to generate mutations: " + exception.getMessage());
        }
    }

    private void runSelectedMutation() {
        int selectedRow = mutationTable.getSelectedRow();
        if (selectedRow < 0) {
            statusLabel.setText("Select a generated mutation case before running it.");
            return;
        }

        MutationCase mutationCase = mutationTableModel.caseAt(
                mutationTable.convertRowIndexToModel(selectedRow)
        );
        ExecutionPlan plan;
        try {
            plan = workflowService.createExecutionPlan(mutationCase);
        } catch (RuntimeException exception) {
            statusLabel.setText("Unable to build execution plan: " + exception.getMessage());
            return;
        }
        ExecutionPolicy policy = new ExecutionPolicy(
                (Integer) maximumRequests.getValue(),
                Duration.ofMillis((Integer) delayMilliseconds.getValue()),
                true,
                inScopeOnly.isSelected()
        );
        boolean stateChanging = plan.steps().stream()
                .map(planned -> planned.step())
                .anyMatch(this::isStateChanging);
        boolean confirmed = !stateChanging || confirmStateChangingRun(plan, policy);
        if (!confirmed) {
            statusLabel.setText("Execution cancelled before sending any request.");
            return;
        }

        runSelected.setEnabled(false);
        runStatus.setText("Running '" + mutationCase.name() + "'...");
        statusLabel.setText("Executing requests sequentially in the background...");
        executionCoordinator.execute(plan, policy, stateChanging)
                .whenComplete((run, throwable) -> SwingUtilities.invokeLater(
                        () -> handleRunCompleted(run, throwable)
                ));
    }

    private boolean confirmStateChangingRun(
            ExecutionPlan plan,
            ExecutionPolicy policy
    ) {
        StringBuilder sequence = new StringBuilder();
        for (int index = 0; index < plan.steps().size(); index++) {
            var planned = plan.steps().get(index);
            WorkflowStep step = planned.step();
            sequence.append(index + 1)
                    .append(". ")
                    .append('[')
                    .append(planned.phase())
                    .append("] [")
                    .append(actorName(plan, step.actorId()))
                    .append("] ")
                    .append(step.method())
                    .append(' ')
                    .append(step.url())
                    .append(System.lineSeparator());
        }

        JTextArea preview = new JTextArea(sequence.toString(), 10, 90);
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, preview.getFont().getSize()));
        preview.setCaretPosition(0);
        JScrollPane previewPane = new JScrollPane(preview);
        previewPane.setPreferredSize(new Dimension(760, 210));

        int result = JOptionPane.showConfirmDialog(
                dialogParent(),
                new Object[]{
                        "This case contains state-changing requests.",
                        "Requests: " + plan.steps().size()
                                + " / cap " + policy.maximumRequests()
                                + "; delay: " + policy.delayBetweenRequests().toMillis() + " ms",
                        "Review the exact sequence before authorizing execution:",
                        previewPane
                },
                "Confirm state-changing WorkflowGuard run",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        return result == JOptionPane.OK_OPTION;
    }

    private void handleRunCompleted(ExecutionRun run, Throwable throwable) {
        runSelected.setEnabled(true);
        if (throwable != null) {
            Throwable cause = throwable instanceof CompletionException
                    && throwable.getCause() != null
                    ? throwable.getCause()
                    : throwable;
            reportUnexpectedError(cause);
            runStatus.setText("Run failed before execution.");
            statusLabel.setText("Unable to execute: " + cause.getMessage());
            return;
        }

        for (int index = 0; index < runHistory.size(); index++) {
            runHistory.set(index, runHistory.get(index).withoutHttpEvidence());
        }
        runHistory.add(0, run);
        if (runHistory.size() > MAXIMUM_RUN_HISTORY) {
            runHistory.removeLast();
        }
        resultMatrixTableModel.setRuns(runHistory);
        if (!runHistory.isEmpty()) {
            resultMatrixTable.setRowSelectionInterval(0, 0);
        }
        showRun(run);
        if (publishBurpIssues.isSelected()) {
            try {
                int published = auditIssuePublisher.publish(run);
                if (published > 0) {
                    String publication = burpIssueViewerAvailable
                            ? "published " + published + " informational Burp issue(s)."
                            : "submitted " + published
                                    + " informational Burp issue(s); All issues is Pro-only, "
                                    + "so review evidence in WorkflowGuard.";
                    statusLabel.setText(
                            "Run '" + run.mutationCaseName() + "': " + run.status()
                                    + "; " + publication
                    );
                }
            } catch (RuntimeException exception) {
                reportUnexpectedError(exception);
                statusLabel.setText(
                        "Run completed, but Burp issue publication failed: "
                                + exception.getMessage()
                );
            }
        }
    }

    void reportUnexpectedError(Throwable throwable) {
        try {
            errorReporter.accept(throwable);
        } catch (RuntimeException ignored) {
            // Error reporting must never replace the original user-facing failure.
        }
    }

    Component dialogParent() {
        return dialogParent;
    }

    private void showRun(ExecutionRun run) {
        lastRun = run;
        exportEvidence.setEnabled(true);
        runResultTableModel.setResults(run.stepResults());
        invariantResultTableModel.setResults(run.invariantResults());
        String message = run.messages().isEmpty()
                ? ""
                : " — " + String.join(" ", run.messages());
        runStatus.setText(
                run.status() + " — " + run.stepResults().size() + " request(s)"
                        + ", " + run.variables().size() + " variable(s)"
                        + ", " + run.invariantResults().size() + " invariant check(s)"
                        + " in " + run.duration().toMillis() + " ms" + message
        );
        statusLabel.setText("Run '" + run.mutationCaseName() + "': " + run.status() + ".");
        if (!run.stepResults().isEmpty()) {
            runResultTable.setRowSelectionInterval(0, 0);
        } else {
            executionEvidence.setText(String.join(System.lineSeparator(), run.messages()));
        }
    }

    private void clearResultMatrix() {
        runHistory.clear();
        resultMatrixTableModel.setRuns(List.of());
        runResultTableModel.setResults(List.of());
        invariantResultTableModel.setResults(List.of());
        executionEvidence.setText("");
        lastRun = null;
        exportEvidence.setEnabled(false);
        runStatus.setText("No run yet.");
        statusLabel.setText("Cleared the in-memory result matrix.");
    }

    private void exportLastRun() {
        if (lastRun == null) {
            statusLabel.setText("Run a mutation before exporting evidence.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export redacted WorkflowGuard evidence");
        chooser.setSelectedFile(new java.io.File(
                "workflowguard-evidence-" + lastRun.id() + ".json"
        ));
        if (chooser.showSaveDialog(dialogParent()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destination = chooser.getSelectedFile().toPath();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            destination = destination.resolveSibling(destination.getFileName() + ".json");
        }
        try {
            if (!confirmOverwrite(destination)) {
                return;
            }
            evidenceExporter.write(destination, lastRun);
            statusLabel.setText("Exported redacted evidence to " + destination + ".");
        } catch (IOException exception) {
            statusLabel.setText("Unable to export evidence: " + exception.getMessage());
            JOptionPane.showMessageDialog(
                    dialogParent(),
                    exception.getMessage(),
                    "Unable to export evidence",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void exportWorkflow() {
        Workflow workflow = workflowService.activeWorkflow().orElse(null);
        if (workflow == null) {
            statusLabel.setText("No active workflow to export.");
            return;
        }
        JCheckBox includeSecrets = new JCheckBox(
                "Include session cookies, authorization headers, and sensitive stale values"
        );
        int confirmation = JOptionPane.showConfirmDialog(
                dialogParent(),
                new Object[]{
                        "Portable exports apply best-effort secret redaction.",
                        "Review every exported file before sharing it.",
                        "Only include secrets when the destination is trusted.",
                        includeSecrets
                },
                "Export workflow",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirmation != JOptionPane.OK_OPTION) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export WorkflowGuard workflow");
        chooser.setSelectedFile(new java.io.File(
                workflow.name().replaceAll("[^A-Za-z0-9._-]+", "-")
                        + ".workflowguard.json"
        ));
        if (chooser.showSaveDialog(dialogParent()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destination = withJsonExtension(chooser.getSelectedFile().toPath());
        try {
            if (!confirmOverwrite(destination)) {
                return;
            }
            workflowArchiveService.write(destination, workflow, includeSecrets.isSelected());
            statusLabel.setText("Exported workflow to " + destination + ".");
        } catch (IOException exception) {
            showIoError("Unable to export workflow", exception);
        }
    }

    private void importWorkflow() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import WorkflowGuard workflow");
        if (chooser.showOpenDialog(dialogParent()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Workflow imported = workflowService.importWorkflow(
                    workflowArchiveService.read(chooser.getSelectedFile().toPath())
            );
            mutationTableModel.setCases(List.of());
            statusLabel.setText(
                    "Imported workflow '" + imported.name() + "'."
                            + (workflowArchiveService.containsRedactions(imported)
                            ? " Replace redacted session values before replay."
                            : "")
            );
        } catch (IOException | RuntimeException exception) {
            showIoError("Unable to import workflow", exception);
        }
    }

    private Path withJsonExtension(Path destination) {
        return destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? destination
                : destination.resolveSibling(destination.getFileName() + ".json");
    }

    private boolean confirmOverwrite(Path destination) {
        if (!Files.exists(destination)) {
            return true;
        }
        return JOptionPane.showConfirmDialog(
                dialogParent(),
                "The file already exists. Replace it?\n" + destination,
                "Confirm file replacement",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.OK_OPTION;
    }

    private void showIoError(String title, Exception exception) {
        statusLabel.setText(title + ": " + exception.getMessage());
        JOptionPane.showMessageDialog(
                dialogParent(),
                exception.getMessage(),
                title,
                JOptionPane.ERROR_MESSAGE
        );
    }

    private void updateExecutionEvidence() {
        int selectedRow = runResultTable.getSelectedRow();
        if (selectedRow < 0 || selectedRow >= runResultTableModel.getRowCount()) {
            executionEvidence.setText("");
            return;
        }
        StepExecutionResult result = runResultTableModel.resultAt(
                runResultTable.convertRowIndexToModel(selectedRow)
        );
        String error = result.errorMessage()
                .map(message -> System.lineSeparator()
                        + "=== ERROR ==="
                        + System.lineSeparator()
                        + message)
                .orElse("");
        String extracted = result.extractedVariables().isEmpty()
                ? ""
                : System.lineSeparator()
                        + System.lineSeparator()
                        + "=== EXTRACTED VARIABLES ==="
                        + System.lineSeparator()
                        + result.extractedVariables().entrySet().stream()
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .collect(Collectors.joining(System.lineSeparator()));
        executionEvidence.setText(
                "=== PHASE ==="
                        + System.lineSeparator()
                        + result.phase()
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "=== REQUEST ==="
                        + System.lineSeparator()
                        + result.rawRequest()
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "=== RESPONSE ==="
                        + System.lineSeparator()
                        + result.rawResponse()
                        + extracted
                        + error
        );
        executionEvidence.setCaretPosition(0);
    }

    private boolean isStateChanging(WorkflowStep step) {
        return safetyGate.isStateChanging(step);
    }

    private void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }

        UUID selectedStepId = Optional.ofNullable(selectedStep())
                .map(WorkflowStep::id)
                .orElse(null);
        refreshing = true;
        try {
            WorkflowSnapshot snapshot = workflowService.snapshot();
            Optional<UUID> selectedWorkflowId = snapshot.activeWorkflowId();
            workflowListModel.clear();
            snapshot.workflows().forEach(workflowListModel::addElement);

            Workflow active = null;
            for (int index = 0; index < workflowListModel.size(); index++) {
                Workflow workflow = workflowListModel.get(index);
                if (selectedWorkflowId.isPresent()
                        && workflow.id().equals(selectedWorkflowId.get())) {
                    workflowList.setSelectedIndex(index);
                    active = workflow;
                    break;
                }
            }
            List<ActorDefinition> actors = active == null ? List.of() : active.actors();
            actorTableModel.setActors(actors);
            actorEditor.removeAllItems();
            actors.forEach(actorEditor::addItem);
            stepTableModel.setSteps(
                    active == null ? List.of() : active.steps(),
                    actors
            );
            variableTableModel.setVariables(
                    active == null ? List.of() : active.variables(),
                    active == null ? List.of() : active.steps()
            );
            invariantTableModel.setInvariants(
                    active == null ? List.of() : active.invariants(),
                    active == null ? List.of() : active.steps()
            );
            List<WorkflowDependency> dependencies = active == null
                    ? List.of()
                    : dependencySuggester.suggest(active);
            dependencyTableModel.setDependencies(
                    dependencies,
                    active == null ? List.of() : active.steps()
            );
            workflowGraphPanel.setWorkflow(active, dependencies);
            long highConfidence = dependencies.stream()
                    .filter(dependency ->
                            dependency.confidence() == DependencyConfidence.HIGH)
                    .count();
            dependencySummary.setText(
                    dependencies.size() + " suggestion(s), "
                            + highConfidence + " high-confidence."
            );
            restoreStepSelection(selectedStepId);
            updateSelectedStepView();
        } finally {
            refreshing = false;
        }
    }

    private void restoreStepSelection(UUID stepId) {
        if (stepId == null) {
            stepTable.clearSelection();
            return;
        }
        for (int row = 0; row < stepTableModel.getRowCount(); row++) {
            if (stepTableModel.stepAt(row).id().equals(stepId)) {
                stepTable.setRowSelectionInterval(row, row);
                return;
            }
        }
        stepTable.clearSelection();
    }

    private void selectStepInTable(UUID stepId) {
        for (int row = 0; row < stepTableModel.getRowCount(); row++) {
            if (stepTableModel.stepAt(row).id().equals(stepId)) {
                stepTable.setRowSelectionInterval(row, row);
                stepTable.scrollRectToVisible(stepTable.getCellRect(row, 0, true));
                workflowGraphPanel.setSelectedStep(stepId);
                return;
            }
        }
    }

    private void updateSelectedStepView() {
        WorkflowStep step = selectedStep();
        boolean selected = step != null;
        categoryEditor.setEnabled(selected);
        roleEditor.setEnabled(selected);
        actorEditor.setEnabled(selected);
        enabledEditor.setEnabled(selected);
        if (!selected) {
            rawRequest.setText("");
            return;
        }
        categoryEditor.setSelectedItem(step.category());
        roleEditor.setSelectedItem(step.role());
        for (int index = 0; index < actorEditor.getItemCount(); index++) {
            if (actorEditor.getItemAt(index).id().equals(step.actorId())) {
                actorEditor.setSelectedIndex(index);
                break;
            }
        }
        enabledEditor.setSelected(step.enabled());
        rawRequest.setText(step.rawRequest());
        rawRequest.setCaretPosition(0);
    }

    private WorkflowStep selectedStep() {
        int selectedRow = stepTable.getSelectedRow();
        if (selectedRow < 0 || selectedRow >= stepTableModel.getRowCount()) {
            return null;
        }
        return stepTableModel.stepAt(stepTable.convertRowIndexToModel(selectedRow));
    }

    private String actorName(ExecutionPlan plan, UUID actorId) {
        return plan.actors().stream()
                .filter(actor -> actor.id().equals(actorId))
                .map(ActorDefinition::name)
                .findFirst()
                .orElse(actorId.toString());
    }

    @Override
    public void close() {
        executionCoordinator.close();
        try {
            changeRegistration.close();
        } catch (Exception ignored) {
            // Listener removal is best-effort during extension unload.
        }
    }

    private static final class StepTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Step", "Method", "URL", "Category", "Role", "Actor", "Enabled", "In scope"
        };
        private List<WorkflowStep> steps = List.of();
        private Map<UUID, String> actorNames = Map.of();

        void setSteps(List<WorkflowStep> steps, List<ActorDefinition> actors) {
            this.steps = List.copyOf(steps);
            actorNames = actors.stream().collect(Collectors.toUnmodifiableMap(
                    ActorDefinition::id,
                    ActorDefinition::name
            ));
            fireTableDataChanged();
        }

        WorkflowStep stepAt(int row) {
            return steps.get(row);
        }

        @Override
        public int getRowCount() {
            return steps.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            WorkflowStep step = steps.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> step.name();
                case 1 -> step.method();
                case 2 -> step.url();
                case 3 -> step.category();
                case 4 -> step.role();
                case 5 -> actorNames.getOrDefault(step.actorId(), step.actorId().toString());
                case 6 -> step.enabled();
                case 7 -> step.inScope();
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 6 || columnIndex == 7 ? Boolean.class : Object.class;
        }
    }

    private static final class ActorTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Actor", "Initial cookie", "Initial authorization",
                "Use captured cookie", "Default"
        };
        private List<ActorDefinition> actors = List.of();

        void setActors(List<ActorDefinition> actors) {
            this.actors = List.copyOf(actors);
            fireTableDataChanged();
        }

        ActorDefinition actorAt(int row) {
            return actors.get(row);
        }

        @Override
        public int getRowCount() {
            return actors.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ActorDefinition actor = actors.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> actor.name();
                case 1 -> actor.initialCookieHeader().isBlank()
                        ? "—"
                        : "<configured>";
                case 2 -> actor.initialAuthorizationHeader().isBlank()
                        ? "—"
                        : "<configured>";
                case 3 -> actor.seedFromCapturedRequest();
                case 4 -> actor.id().equals(ActorDefinition.DEFAULT_ID);
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 3 || columnIndex == 4 ? Boolean.class : Object.class;
        }
    }

    private static final class MutationTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Case", "Type", "Sequence", "Description"
        };
        private List<MutationCase> cases = List.of();

        void setCases(List<MutationCase> cases) {
            this.cases = List.copyOf(cases);
            fireTableDataChanged();
        }

        MutationCase caseAt(int row) {
            return cases.get(row);
        }

        @Override
        public int getRowCount() {
            return cases.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MutationCase mutation = cases.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> mutation.name();
                case 1 -> mutation.type();
                case 2 -> mutation.steps().stream()
                        .map(WorkflowGuardTab::shortStepName)
                        .collect(Collectors.joining(" → "));
                case 3 -> mutation.description();
                default -> "";
            };
        }
    }

    private static final class RunResultTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Phase", "Actor", "Step", "Method", "HTTP status", "Duration", "Outcome"
        };
        private List<StepExecutionResult> results = List.of();

        void setResults(List<StepExecutionResult> results) {
            this.results = List.copyOf(results);
            fireTableDataChanged();
        }

        StepExecutionResult resultAt(int row) {
            return results.get(row);
        }

        @Override
        public int getRowCount() {
            return results.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            StepExecutionResult result = results.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> result.phase();
                case 1 -> result.actorName();
                case 2 -> result.stepName();
                case 3 -> result.method();
                case 4 -> result.statusCode()
                        .map(String::valueOf)
                        .orElse("—");
                case 5 -> String.format(
                        Locale.ROOT,
                        "%.1f ms",
                        result.duration().toNanos() / 1_000_000.0
                );
                case 6 -> result.status();
                default -> "";
            };
        }
    }

    private static final class ResultMatrixTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Time",
                "Case",
                "Run",
                "Actors",
                "Mutation HTTP",
                "Checks passed",
                "Mutation violations",
                "Cleanup violations",
                "Assessment",
                "Duration"
        };
        private static final DateTimeFormatter TIME_FORMATTER =
                DateTimeFormatter.ofPattern("HH:mm:ss")
                        .withZone(ZoneId.systemDefault());
        private final ExecutionRunSummarizer summarizer = new ExecutionRunSummarizer();
        private List<ExecutionRun> runs = List.of();
        private List<ExecutionRunSummary> summaries = List.of();

        void setRuns(List<ExecutionRun> runs) {
            this.runs = List.copyOf(runs);
            summaries = this.runs.stream().map(summarizer::summarize).toList();
            fireTableDataChanged();
        }

        ExecutionRun runAt(int row) {
            return runs.get(row);
        }

        @Override
        public int getRowCount() {
            return summaries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ExecutionRunSummary summary = summaries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> TIME_FORMATTER.format(summary.startedAt());
                case 1 -> summary.mutationCaseName();
                case 2 -> summary.runStatus();
                case 3 -> summary.actors();
                case 4 -> summary.mutationHttpStatuses();
                case 5 -> summary.passedInvariantChecks();
                case 6 -> summary.mutationViolations();
                case 7 -> summary.cleanupViolations();
                case 8 -> summary.assessment();
                case 9 -> summary.duration().toMillis() + " ms";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex >= 5 && columnIndex <= 7
                    ? Integer.class
                    : Object.class;
        }
    }

    private static final class VariableTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Name", "Source step", "Extraction", "Expression", "Capture group", "Stale value"
        };
        private List<VariableDefinition> variables = List.of();
        private Map<UUID, String> stepNames = Map.of();

        void setVariables(List<VariableDefinition> variables, List<WorkflowStep> steps) {
            this.variables = List.copyOf(variables);
            stepNames = steps.stream().collect(Collectors.toUnmodifiableMap(
                    WorkflowStep::id,
                    WorkflowStep::name
            ));
            fireTableDataChanged();
        }

        VariableDefinition variableAt(int row) {
            return variables.get(row);
        }

        @Override
        public int getRowCount() {
            return variables.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            VariableDefinition variable = variables.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> variable.name();
                case 1 -> stepNames.getOrDefault(
                        variable.sourceStepId(),
                        variable.sourceStepId().toString()
                );
                case 2 -> variable.extractionType();
                case 3 -> variable.expression();
                case 4 -> variable.extractionType() == ExtractionType.REGEX
                        ? variable.captureGroup()
                        : "—";
                case 5 -> variable.hasStaleValue() ? "Configured" : "—";
                default -> "";
            };
        }
    }

    private static final class InvariantTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Name", "Probe step", "JSON Pointer", "Rule", "Expected JSON / expression"
        };
        private List<ProbeInvariant> invariants = List.of();
        private Map<UUID, String> stepNames = Map.of();

        void setInvariants(List<ProbeInvariant> invariants, List<WorkflowStep> steps) {
            this.invariants = List.copyOf(invariants);
            stepNames = steps.stream().collect(Collectors.toUnmodifiableMap(
                    WorkflowStep::id,
                    WorkflowStep::name
            ));
            fireTableDataChanged();
        }

        ProbeInvariant invariantAt(int row) {
            return invariants.get(row);
        }

        @Override
        public int getRowCount() {
            return invariants.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ProbeInvariant definition = invariants.get(rowIndex);
            JsonInvariant invariant = definition.invariant();
            return switch (columnIndex) {
                case 0 -> invariant.name();
                case 1 -> stepNames.getOrDefault(
                        definition.probeStepId(),
                        definition.probeStepId().toString()
                );
                case 2 -> invariant.jsonPointer().isEmpty() ? "/" : invariant.jsonPointer();
                case 3 -> invariant.rule();
                case 4 -> invariant.expectedJson() == null ? "—" : invariant.expectedJson();
                default -> "";
            };
        }
    }

    private static final class InvariantResultTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Invariant", "Comparison", "Passed", "Details"
        };
        private List<InvariantCheckResult> results = List.of();

        void setResults(List<InvariantCheckResult> results) {
            this.results = List.copyOf(results);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return results.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            InvariantCheckResult result = results.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> result.invariantName();
                case 1 -> result.comparisonPhase();
                case 2 -> result.passed();
                case 3 -> details(result);
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 2 ? Boolean.class : Object.class;
        }

        private String details(InvariantCheckResult result) {
            if (result.errorMessage().isPresent()) {
                return result.errorMessage().get();
            }
            if (result.violations().isEmpty()) {
                return "Passed";
            }
            return result.violations().stream()
                    .map(violation -> violation.jsonPointer() + ": " + violation.message())
                    .collect(Collectors.joining("; "));
        }
    }

    private static final class WorkflowDependencyTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "From", "To", "Type", "Confidence", "Evidence"
        };
        private List<WorkflowDependency> dependencies = List.of();
        private Map<UUID, String> stepNames = Map.of();

        void setDependencies(
                List<WorkflowDependency> dependencies,
                List<WorkflowStep> steps
        ) {
            this.dependencies = List.copyOf(dependencies);
            stepNames = steps.stream().collect(Collectors.toUnmodifiableMap(
                    WorkflowStep::id,
                    WorkflowStep::name
            ));
            fireTableDataChanged();
        }

        WorkflowDependency dependencyAt(int row) {
            return dependencies.get(row);
        }

        @Override
        public int getRowCount() {
            return dependencies.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            WorkflowDependency dependency = dependencies.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> stepNames.getOrDefault(
                        dependency.sourceStepId(),
                        dependency.sourceStepId().toString()
                );
                case 1 -> stepNames.getOrDefault(
                        dependency.targetStepId(),
                        dependency.targetStepId().toString()
                );
                case 2 -> dependency.type();
                case 3 -> dependency.confidence();
                case 4 -> dependency.reason();
                default -> "";
            };
        }
    }

    private static String shortStepName(WorkflowStep step) {
        int separator = step.name().indexOf(" — ");
        return separator > 0 ? step.name().substring(0, separator) : step.name();
    }

    private record ActorForm(
            JPanel panel,
            JTextField name,
            JTextField initialCookie,
            JTextField initialAuthorization,
            JCheckBox seedFromCaptured
    ) {
    }
}
