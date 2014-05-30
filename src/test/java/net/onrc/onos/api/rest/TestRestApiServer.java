package net.onrc.onos.api.rest;


import net.floodlightcontroller.restserver.RestletRoutable;
import net.onrc.onos.core.intent.runtime.web.IntentWebRoutable;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.routing.Filter;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import org.restlet.service.StatusService;

import java.util.List;

/**
 * A REST API server suitible for inclusion in unit tests.  Unit tests can
 * create a server on a given port, then specify the RestletRoutable classes
 * that are to be tested.  The lifecyle for the server is to create it
 * and then start it during the @Before (setUp) portion of the test and to
 * shut it down during the @After (tearDown) section.
 */
public class TestRestApiServer {

    private List<RestletRoutable> restlets;
    private RestApplication restApplication;
    private Server server;
    private Component component;
    private int port;

    /**
     * Hide the default constructor.
     */
    @SuppressWarnings("unused")
    private TestRestApiServer() { }

    /**
     * Public constructor.  Given a port number, create a REST API server on
     * that port.  The server is not running, it can be started using the
     * startServer() method.
     * @param serverPort port for the server to listen on.
     */
    public TestRestApiServer(final int serverPort) {
        port = serverPort;
    }

    /**
     * The restlet engine requires an Application as a container.
     */
    private class RestApplication extends Application {
        private Context context;

        /**
         * Initialize the Application along with its Context.
         */
        public RestApplication() {
            super();
            context = new Context();
        }

        /**
         * Add an attribute to the Context for the Application.  This is most
         * often used to specify attributes that allow modules to locate each
         * other.
         *
         * @param name name of the attribute
         * @param value value of the attribute
         */
        public void addAttribute(final String name, final Object value) {
            context.getAttributes().put(name, value);
        }

        /**
         * Sets up the Restlet for the APIs under test using a Router.  Also, a
         * filter is installed to deal with double slashes in URLs.
         * This code is adapted from
         * net.floodlightcontroller.restserver.RestApiServer
         *
         * @return Router object for the APIs under test.
         */
        @Override
        public Restlet createInboundRoot() {
            Router baseRouter = new Router(context);
            baseRouter.setDefaultMatchingMode(Template.MODE_STARTS_WITH);
            for (RestletRoutable rr : restlets) {
                baseRouter.attach(rr.basePath(), rr.getRestlet(context));
            }

            /**
             * Filter out multiple slashes in URLs to make them a single slash.
             */
            Filter slashFilter = new Filter() {
                @Override
                protected int beforeHandle(Request request, Response response) {
                    Reference ref = request.getResourceRef();
                    String originalPath = ref.getPath();
                    if (originalPath.contains("//")) {
                        String newPath = originalPath.replaceAll("/+", "/");
                        ref.setPath(newPath);
                    }
                    return Filter.CONTINUE;
                }

            };
            slashFilter.setNext(baseRouter);

            return slashFilter;
        }

        /**
         * Run the Application on a given port.
         *
         * @param restPort port to listen on for inbounde requests
         */
        public void run(final int restPort) {
            setStatusService(new StatusService() {
                @Override
                public Representation getRepresentation(Status status,
                                                        Request request,
                                                        Response response) {
                    return new JacksonRepresentation<>(status);
                }
            });

            // Start listening for REST requests
            try {
                component = new Component();
                server = component.getServers().add(Protocol.HTTP, restPort);
                component.getDefaultHost().attach(this);
                component.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Start up the REST server.  A list of the Restlets being tested is
     * passed in.  The usual use of this method is in the @Before (startUp)
     * of a JUnit test.
     *
     * @param restletsUnderTest list of Restlets to run as part of the server.
     * @throws Exception if starting the server fails.
     */
    public void startServer(final List<RestletRoutable> restletsUnderTest)
           throws Exception {
        restlets = restletsUnderTest;

        restApplication = new RestApplication();
        restApplication.run(port);

    }

    /**
     * Stop the REST server.  The container is stopped, and the server will
     * no longer respond to requests.  The usual use of this is in the @After
     * (tearDown) part of the server.
     *
     * @throws Exception if the server cannot be shut down cleanly.
     */
    public void stopServer() throws Exception {
        restApplication.stop();
        server.stop();
        component.stop();
    }


    /**
     * Add an attribute to the Context for the Application.  This is most
     * often used to specify attributes that allow modules to locate each
     * other.
     *
     * @param name name of the attribute
     * @param value value of the attribute
     */
    public void addAttribute(final String name, final Object value) {
        restApplication.addAttribute(name, value);
    }
}