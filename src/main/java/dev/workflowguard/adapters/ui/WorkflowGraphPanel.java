package dev.workflowguard.adapters.ui;

import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.DependencyConfidence;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowDependency;
import dev.workflowguard.domain.WorkflowStep;

import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

final class WorkflowGraphPanel extends JComponent {
    private static final int LEFT_MARGIN = 105;
    private static final int TOP_MARGIN = 6;
    private static final int NODE_WIDTH = 190;
    private static final int NODE_HEIGHT = 44;
    private static final int COLUMN_GAP = 52;
    private static final int LANE_GAP = 52;

    private final Consumer<UUID> selectionListener;
    private final Map<UUID, Rectangle> nodeBounds = new LinkedHashMap<>();
    private Workflow workflow;
    private List<WorkflowDependency> dependencies = List.of();
    private UUID selectedStepId;

    WorkflowGraphPanel(Consumer<UUID> selectionListener) {
        this.selectionListener = selectionListener;
        setOpaque(true);
        getAccessibleContext().setAccessibleName("Workflow dependency graph");
        getAccessibleContext().setAccessibleDescription(
                "Action, probe, and cleanup steps connected by suggested dependencies."
        );
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                UUID selected = stepAt(event.getPoint());
                if (selected != null) {
                    setSelectedStep(selected);
                    selectionListener.accept(selected);
                }
            }
        });
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleWorkflowGraph();
        }
        return accessibleContext;
    }

    void setWorkflow(Workflow workflow, List<WorkflowDependency> dependencies) {
        this.workflow = workflow;
        this.dependencies = List.copyOf(dependencies);
        layoutNodes();
        revalidate();
        repaint();
    }

    void setSelectedStep(UUID stepId) {
        selectedStepId = stepId;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int columns = workflow == null ? 1 : Math.max(1, workflow.steps().size());
        return new Dimension(
                Math.max(760, LEFT_MARGIN + columns * (NODE_WIDTH + COLUMN_GAP)),
                TOP_MARGIN + 3 * LANE_GAP + 8
        );
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D draw = (Graphics2D) graphics.create();
        try {
            draw.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            draw.setColor(color("Panel.background", getBackground()));
            draw.fillRect(0, 0, getWidth(), getHeight());
            drawLanes(draw);
            drawDependencies(draw);
            drawNodes(draw);
        } finally {
            draw.dispose();
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        UUID stepId = stepAt(event.getPoint());
        if (stepId == null || workflow == null) {
            return null;
        }
        WorkflowStep step = workflow.steps().stream()
                .filter(candidate -> candidate.id().equals(stepId))
                .findFirst()
                .orElse(null);
        if (step == null) {
            return null;
        }
        String actor = workflow.actors().stream()
                .filter(candidate -> candidate.id().equals(step.actorId()))
                .map(ActorDefinition::name)
                .findFirst()
                .orElse(step.actorId().toString());
        return "<html><b>" + escape(step.name()) + "</b><br>"
                + escape(step.method() + " " + step.url()) + "<br>"
                + "Role: " + step.role() + "; actor: " + escape(actor) + "</html>";
    }

    private void layoutNodes() {
        nodeBounds.clear();
        if (workflow == null) {
            return;
        }
        for (int index = 0; index < workflow.steps().size(); index++) {
            WorkflowStep step = workflow.steps().get(index);
            int x = LEFT_MARGIN + index * (NODE_WIDTH + COLUMN_GAP);
            int y = TOP_MARGIN + laneIndex(step.role()) * LANE_GAP;
            nodeBounds.put(step.id(), new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT));
        }
    }

    private void drawLanes(Graphics2D draw) {
        Font original = draw.getFont();
        draw.setFont(original.deriveFont(Font.BOLD));
        Color muted = color("Label.disabledForeground", color("Label.foreground", Color.GRAY));
        Color separator = color("Separator.foreground", muted);
        for (StepRole role : StepRole.values()) {
            int lane = laneIndex(role);
            int y = TOP_MARGIN + lane * LANE_GAP;
            draw.setColor(muted);
            draw.drawString(role.name(), 14, y + NODE_HEIGHT / 2 + 5);
            draw.setColor(withAlpha(separator, 80));
            draw.drawLine(
                    LEFT_MARGIN - 12,
                    y + NODE_HEIGHT + 4,
                    getWidth() - 20,
                    y + NODE_HEIGHT + 4
            );
        }
        draw.setFont(original);
    }

    private void drawDependencies(Graphics2D draw) {
        Map<String, WorkflowDependency> strongestByPair = new LinkedHashMap<>();
        for (WorkflowDependency dependency : dependencies) {
            String key = dependency.sourceStepId() + ":" + dependency.targetStepId();
            WorkflowDependency current = strongestByPair.get(key);
            if (current == null || strength(dependency.confidence()) > strength(
                    current.confidence()
            )) {
                strongestByPair.put(key, dependency);
            }
        }
        for (WorkflowDependency dependency : strongestByPair.values()) {
            Rectangle source = nodeBounds.get(dependency.sourceStepId());
            Rectangle target = nodeBounds.get(dependency.targetStepId());
            if (source == null || target == null) {
                continue;
            }
            Point start = new Point(source.x + source.width, source.y + source.height / 2);
            Point end = new Point(target.x, target.y + target.height / 2);
            boolean crossesLanes = start.y != end.y;
            java.awt.Shape path;
            if (crossesLanes) {
                int bend = Math.min(50, Math.max(24, (end.x - start.x) / 4));
                Path2D routed = new Path2D.Double();
                routed.moveTo(start.x, start.y);
                routed.curveTo(
                        start.x + bend / 2.0,
                        start.y,
                        start.x + bend / 2.0,
                        end.y,
                        start.x + bend,
                        end.y
                );
                routed.lineTo(end.x, end.y);
                path = routed;
            } else {
                int curve = Math.max(24, Math.abs(end.x - start.x) / 3);
                path = new QuadCurve2D.Double(
                        start.x,
                        start.y,
                        start.x + curve,
                        end.y,
                        end.x,
                        end.y
                );
            }
            draw.setColor(dependencyColor(dependency.confidence()));
            draw.setStroke(new BasicStroke(strokeWidth(dependency.confidence())));
            draw.draw(path);
            drawArrow(draw, end);
            drawDependencyLabel(draw, dependency, start, end, crossesLanes);
        }
    }

    private void drawDependencyLabel(
            Graphics2D draw,
            WorkflowDependency dependency,
            Point start,
            Point end,
            boolean crossesLanes
    ) {
        String label = switch (dependency.type()) {
            case VARIABLE_DATA -> "DATA";
            case RESOURCE_LIFECYCLE -> "LIFECYCLE";
            case OBSERVED_ORDER -> "ORDER";
        };
        Font original = draw.getFont();
        draw.setFont(original.deriveFont(Math.max(10f, original.getSize2D() - 1f)));
        FontMetrics metrics = draw.getFontMetrics();
        int x = (start.x + end.x - metrics.stringWidth(label)) / 2;
        int y = (crossesLanes ? end.y : (start.y + end.y) / 2) - 4;
        Color background = color("Panel.background", getBackground());
        draw.setColor(withAlpha(background, 230));
        draw.fillRect(
                x - 3,
                y - metrics.getAscent(),
                metrics.stringWidth(label) + 6,
                metrics.getHeight()
        );
        draw.setColor(dependencyColor(dependency.confidence()));
        draw.drawString(label, x, y);
        draw.setFont(original);
    }

    private void drawArrow(Graphics2D draw, Point end) {
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(end.x, end.y);
        arrow.lineTo(end.x - 9, end.y - 5);
        arrow.lineTo(end.x - 9, end.y + 5);
        arrow.closePath();
        draw.fill(arrow);
    }

    private void drawNodes(Graphics2D draw) {
        if (workflow == null) {
            return;
        }
        Map<UUID, String> actorNames = new LinkedHashMap<>();
        workflow.actors().forEach(actor -> actorNames.put(actor.id(), actor.name()));
        Font original = draw.getFont();
        Font titleFont = original.deriveFont(Font.BOLD);
        Color background = color("Table.background", getBackground());
        Color foreground = color("Table.foreground", getForeground());
        Color border = color("Separator.foreground", foreground);
        Color selectedBackground = color("Table.selectionBackground", background);
        Color selectedForeground = color("Table.selectionForeground", foreground);
        Color disabled = color("Label.disabledForeground", foreground);

        for (WorkflowStep step : workflow.steps()) {
            Rectangle bounds = nodeBounds.get(step.id());
            boolean selected = step.id().equals(selectedStepId);
            draw.setColor(selected ? selectedBackground : background);
            draw.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 12, 12);
            draw.setColor(selected ? selectedForeground : step.enabled() ? border : disabled);
            draw.setStroke(step.enabled()
                    ? new BasicStroke(selected ? 2.2f : 1.2f)
                    : new BasicStroke(
                            1.2f,
                            BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_MITER,
                            10,
                            new float[]{5, 4},
                            0
                    ));
            draw.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 12, 12);

            Color text = selected ? selectedForeground : step.enabled() ? foreground : disabled;
            draw.setColor(text);
            draw.setFont(titleFont);
            draw.drawString(
                    ellipsize(draw.getFontMetrics(), shortName(step.name()), NODE_WIDTH - 18),
                    bounds.x + 9,
                    bounds.y + 14
            );
            draw.setFont(original);
            draw.drawString(
                    ellipsize(
                            draw.getFontMetrics(),
                            step.method() + " · " + step.category(),
                            NODE_WIDTH - 18
                    ),
                    bounds.x + 9,
                    bounds.y + 28
            );
            String actor = actorNames.getOrDefault(step.actorId(), "Unknown actor");
            String detail = actor + (step.enabled() ? "" : " · disabled");
            draw.drawString(
                    ellipsize(draw.getFontMetrics(), detail, NODE_WIDTH - 18),
                    bounds.x + 9,
                    bounds.y + 41
            );
        }
        draw.setFont(original);
    }

    private UUID stepAt(Point point) {
        return nodeBounds.entrySet().stream()
                .filter(entry -> entry.getValue().contains(point))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private int laneIndex(StepRole role) {
        return switch (role) {
            case ACTION -> 0;
            case PROBE -> 1;
            case CLEANUP -> 2;
        };
    }

    private int strength(DependencyConfidence confidence) {
        return switch (confidence) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    private float strokeWidth(DependencyConfidence confidence) {
        return switch (confidence) {
            case HIGH -> 2.4f;
            case MEDIUM -> 1.7f;
            case LOW -> 1.0f;
        };
    }

    private Color dependencyColor(DependencyConfidence confidence) {
        return switch (confidence) {
            case HIGH -> withAlpha(color("Label.foreground", getForeground()), 210);
            case MEDIUM -> withAlpha(color("Label.foreground", getForeground()), 155);
            case LOW -> withAlpha(
                    color("Label.disabledForeground", getForeground()),
                    130
            );
        };
    }

    private Color color(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private String shortName(String name) {
        int separator = name.indexOf(" — ");
        return separator > 0 ? name.substring(separator + 3) : name;
    }

    private String ellipsize(FontMetrics metrics, String value, int maximumWidth) {
        if (metrics.stringWidth(value) <= maximumWidth) {
            return value;
        }
        String suffix = "…";
        int length = value.length();
        while (length > 0
                && metrics.stringWidth(value.substring(0, length) + suffix) > maximumWidth) {
            length--;
        }
        return value.substring(0, length) + suffix;
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    protected final class AccessibleWorkflowGraph extends AccessibleJComponent {
    }
}
