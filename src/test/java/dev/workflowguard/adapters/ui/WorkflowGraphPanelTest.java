package dev.workflowguard.adapters.ui;

import dev.workflowguard.TestFixtures;
import dev.workflowguard.core.WorkflowDependencySuggester;
import dev.workflowguard.domain.StepCategory;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGraphPanelTest {
    @Test
    void paintsTheWorkflowAndMapsNodeClicksBackToCapturedSteps() throws Exception {
        var create = TestFixtures.step("Create", "POST", StepCategory.CREATE);
        var update = TestFixtures.step("Update", "PATCH", StepCategory.UPDATE);
        var workflow = TestFixtures.workflow(create, update);
        var dependencies = new WorkflowDependencySuggester().suggest(workflow);
        var selected = new AtomicReference<java.util.UUID>();

        SwingUtilities.invokeAndWait(() -> {
            WorkflowGraphPanel panel = new WorkflowGraphPanel(selected::set);
            panel.setWorkflow(workflow, dependencies);
            panel.setSize(panel.getPreferredSize());

            BufferedImage image = new BufferedImage(
                    panel.getWidth(),
                    panel.getHeight(),
                    BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D graphics = image.createGraphics();
            try {
                panel.paint(graphics);
            } finally {
                graphics.dispose();
            }
            panel.dispatchEvent(new MouseEvent(
                    panel,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    120,
                    30,
                    1,
                    false
            ));

            assertTrue(panel.getPreferredSize().width >= 760);
            assertTrue(image.getRGB(120, 30) != 0);
        });

        assertEquals(create.id(), selected.get());
    }
}
