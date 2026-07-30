import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import dev.workflowguard.adapters.burp.WorkflowGuardBootstrap;

public final class Extension implements BurpExtension {
    private WorkflowGuardBootstrap bootstrap;

    @Override
    public void initialize(MontoyaApi api) {
        bootstrap = new WorkflowGuardBootstrap(api);
        bootstrap.initialize();
    }
}
