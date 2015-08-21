package org.datadog.jenkins.plugins.datadogbuildreporter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.util.FormValidation;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

/**
 * DatadogBuildListener {@link RunListener}.
 *
 * <p>When the user configures the project and runs a build,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link DatadogBuildListener} is created. The created instance is persisted to the project
 * configuration XML by using XStream, so this allows you to use instance fields
 * (like {@link #name}) to remember the configuration.
 *
 * <p>When a build finishes, the {@link #onCompleted(Run, TaskListener)} method will be invoked.
 *
 * @author John Zeller
 */

@Extension
public class DatadogBuildListener extends RunListener<Run>
                                  implements Describable<DatadogBuildListener> {
  /**
   * Static variables describing consistent plugin names, Datadog API endpoints/codes, and magic
   * numbers.
   */
  static final String DISPLAY_NAME = "Datadog Build Reporter";
  static final String BASEURL = "https://app.datadoghq.com/api/";
  static final String VALIDATE = "v1/validate";
  static final String METRIC = "v1/series";
  static final String EVENT = "v1/events";
  static final String SERVICECHECK = "v1/check_run";
  static final Integer OK = 0;
  static final Integer WARNING = 1;
  static final Integer CRITICAL = 2;
  static final Integer UNKNOWN = 3;
  static final double THOUSAND_DOUBLE = 1000.0;
  static final long THOUSAND_LONG = 1000L;
  static final Integer HTTP_FORBIDDEN = 403;
  private PrintStream logger = null;

  /**
   * Runs when the {@link DatadogBuildListener} class is created.
   */
  public DatadogBuildListener() { }

  /**
   * Called when a build is first started.
   *
   * @param run - A Run object representing a particular execution of Job.
   * @param listener - A TaskListener object which receives events that happen during some
   *                   operation.
   */
  @Override
  public final void onStarted(final Run run, final TaskListener listener) {
    logger = listener.getLogger();
    listener.getLogger().println("Started build!");
  }

  /**
   * Called when a build is completed.
   *
   * @param run - A Run object representing a particular execution of Job.
   * @param listener - A TaskListener object which receives events that happen during some
   *                   operation.
   */
  @Override
  public final void onCompleted(final Run run, @Nonnull final TaskListener listener) {
    logger = listener.getLogger();
    listener.getLogger().println("Completed build!");

    // Collect Data
    JSONObject builddata = gatherBuildMetadata(run, listener);
    JSONObject payload = assemblePayload(builddata);
    JSONArray tags = assembleTags(builddata);

    // Report Data
    event(builddata, tags);
    gauge("jenkins.job.duration", builddata, "duration", tags);
    if ( "SUCCESS".equals(payload.get("result")) ) {
      serviceCheck("jenkins.job.status", this.OK, builddata, tags);
    } else {
      serviceCheck("jenkins.job.status", this.CRITICAL, builddata, tags);
    }
  }

  /**
   * Gathers build metadata, assembling it into a {@link JSONObject} before
   * returning it to the caller.
   *
   * @param run - A Run object representing a particular execution of Job.
   * @param listener - A TaskListener object which receives events that happen during some
   *                   operation.
   * @return a JSONObject containing a builds metadata.
   */
  private JSONObject gatherBuildMetadata(final Run run, @Nonnull final TaskListener listener) {
    // Grab environment variables
    EnvVars envVars = null;
    try {
      envVars = run.getEnvironment(listener);
    } catch (IOException ex) {
      Logger.getLogger(DatadogBuildListener.class.getName()).log(Level.SEVERE, null, ex);
    } catch (InterruptedException ex) {
      Logger.getLogger(DatadogBuildListener.class.getName()).log(Level.SEVERE, null, ex);
    }

    // Assemble JSON
    long starttime = run.getStartTimeInMillis() / this.THOUSAND_LONG; // adjusted from ms to s
    double duration = run.getDuration() / this.THOUSAND_DOUBLE; // adjusted from ms to s
    long endtime = starttime + (long) duration; // adjusted from ms to s
    JSONObject builddata = new JSONObject();
    builddata.put("starttime", starttime); // long
    builddata.put("duration", duration); // double
    builddata.put("endtime", endtime); // long
    builddata.put("result", run.getResult().toString()); // string
    builddata.put("number", run.number); // int
    builddata.put("job_name", run.getParent().getDisplayName()); // string
    builddata.put("hostname", envVars.get("HOSTNAME")); // string
    builddata.put("node", envVars.get("NODE_NAME")); // string

    if ( envVars.get("GIT_BRANCH") != null ) {
      builddata.put("branch", envVars.get("GIT_BRANCH")); // string
    } else if ( envVars.get("CVS_BRANCH") != null ) {
      builddata.put("branch", envVars.get("CVS_BRANCH")); // string
    }

    return builddata;
  }

  /**
   * Assembles a {@link JSONObject} payload from metadata available in the
   * {@link JSONObject} builddata. Returns a {@link JSONObject} with the new
   * payload.
   *
   * @param builddata - A JSONObject containing a builds metadata.
   * @return a JSONObject containing a specific subset of a builds metadata.
   */
  private JSONObject assemblePayload(final JSONObject builddata) {
    JSONObject payload = new JSONObject();
    payload.put("host", builddata.get("hostname"));
    payload.put("job_name", builddata.get("job_name"));
    payload.put("event_type", "build result");
    payload.put("timestamp", builddata.get("endtime"));
    payload.put("result", builddata.get("result"));
    payload.put("number", builddata.get("number"));
    payload.put("duration", builddata.get("duration"));
    payload.put("node", builddata.get("node"));

    if ( builddata.get("branch") != null ) {
      payload.put("branch", builddata.get("branch"));
    }

    return payload;
  }

  /**
   * Assembles a {@link JSONArray} from metadata available in the
   * {@link JSONObject} builddata. Returns a {@link JSONArray} with the set
   * of tags.
   *
   * @param builddata - A JSONObject containing a builds metadata.
   * @return a JSONArray containing a specific subset of tags retrieved from a builds metadata.
   */
  private JSONArray assembleTags(final JSONObject builddata) {
    JSONArray tags = new JSONArray();
    tags.add("job_name:" + builddata.get("job_name"));
    tags.add("result:" + builddata.get("result"));
    tags.add("build_number:" + builddata.get("number"));
    if ( builddata.get("branch") != null ) {
      tags.add("branch:" + builddata.get("branch"));
    }

    return tags;
  }

  /**
   * Posts a given {@link JSONObject} payload to the DataDog API, using the
   * user configured {@link apiKey}.
   *
   * @param payload - A JSONObject containing a specific subset of a builds metadata.
   * @param type - A String containing the URL subpath pertaining to the type of API post required.
   * @return a boolean to signify the success or failure of the HTTP POST request.
   */
  public final Boolean post(final JSONObject payload, final String type) {
    String urlParameters = "?api_key=" + getDescriptor().getApiKey();
    HttpURLConnection conn = null;

    try {
      // Make request
      URL url = new URL(this.BASEURL + type + urlParameters);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setUseCaches(false);
      conn.setDoInput(true);
      conn.setDoOutput(true);

      // Send request
      DataOutputStream wr = new DataOutputStream( conn.getOutputStream() );
      wr.writeBytes( payload.toString() );
      wr.flush();
      wr.close();

      // Get response
      BufferedReader rd = new BufferedReader( new InputStreamReader( conn.getInputStream() ) );
      StringBuilder result = new StringBuilder();
      String line;
      while ( (line = rd.readLine()) != null ) {
        result.append(line);
      }
      rd.close();
      JSONObject json = (JSONObject) JSONSerializer.toJSON( result.toString() );
      if ( "ok".equals(json.getString("status")) ) {
        logger.println("API call of type '" + type + "' was sent successfully!");
        logger.println("Payload: " + payload.toString());
        return true;
      } else {
        logger.println("API call of type '" + type + "' failed!");
        logger.println("Payload: " + payload.toString());
        return false;
      }
    } catch (Exception e) {
      if ( conn.getResponseCode() == this.HTTP_FORBIDDEN ) {
        logger.println("Hmmm, your API key may be invalid. We received a 403 error.");
        return false;
      }
      logger.println("Client error: " + e);
      return false;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
      return true;
    }
  }

  /**
   * Sends a metric to the Datadog API, including the gauge name, value, and
   * a set of tags.
   *
   * @param metricName - A String with the name of the metric to record.
   * @param builddata - A JSONObject containing a builds metadata.
   * @param key - A String with the name of the build metadata to be found in the {@link JSONObject}
   *              builddata.
   * @param tags - A JSONArray containing a specific subset of tags retrieved from a builds
   *               metadata.
   */
  public final void gauge(final String metricName, final JSONObject builddata, final String key,
                          final JSONArray tags) {
    logger.println("Sending metric '" + metricName + "' with value "
                   + builddata.get(key).toString());

    // Setup data point, of type [<unix_timestamp>, <value>]
    JSONArray points = new JSONArray();
    JSONArray point = new JSONArray();
    point.add(System.currentTimeMillis() / this.THOUSAND_LONG); // current time in s
    point.add(builddata.get(key));
    points.add(point); // api expects a list of points

    // Build metric
    JSONObject metric = new JSONObject();
    metric.put("metric", metricName);
    metric.put("points", points);
    metric.put("type", "gauge");
    metric.put("host", builddata.get("hostname"));
    metric.put("tags", tags);

    // Place metric as item of series list
    JSONArray series = new JSONArray();
    series.add(metric);

    // Add series to payload
    JSONObject payload = new JSONObject();
    payload.put("series", series);

    post(payload, this.METRIC);
  }

  /**
   * Sends a service check to the Datadog API, including the check name,
   * status, and a set of tags.
   *
   * @param checkName - A String with the name of the service check to record.
   * @param status - An Integer with the status code to record for this service check.
   * @param builddata - A JSONObject containing a builds metadata.
   * @param tags - A JSONArray containing a specific subset of tags retrieved from a builds
   *               metadata.
   */
  public final void serviceCheck(final String checkName, final Integer status,
                                 final JSONObject builddata, final JSONArray tags) {
    logger.println("Sending service check '" + checkName + "' with status " + status.toString());

    // Build payload
    JSONObject payload = new JSONObject();
    payload.put("check", checkName);
    payload.put("host_name", builddata.get("hostname"));
    payload.put("timestamp", System.currentTimeMillis() / this.THOUSAND_LONG); // current time in s
    payload.put("status", status);
    payload.put("tags", tags);

    post(payload, this.SERVICECHECK);
  }

  /**
   * Sends a an event to the Datadog API, including the event payload, and a
   * set of tags.
   *
   * @param builddata - A JSONObject containing a builds metadata.
   * @param tags - A JSONArray containing a specific subset of tags retrieved from a builds
   *               metadata.
   */
  public final void event(final JSONObject builddata, final JSONArray tags) {
    logger.println("Sending event");

    // Build payload
    JSONObject payload = new JSONObject();
    String title = builddata.get("job_name").toString();
    if ( "SUCCESS".equals( builddata.get("result") ) ) {
      title = title + " succeeded";
    } else {
      title = title + " failed";
    }
    title = title + " on " + builddata.get("hostname").toString();
    payload.put("title", title);
    payload.put("text", "");
    payload.put("priority", "normal");
    payload.put("tags", tags);
    payload.put("alert_type", "info");

    post(payload, this.EVENT);
  }

  /**
   * Getter function for the {@link DescriptorImpl} class.
   *
   * @return a new {@link DescriptorImpl} class.
   */
  public final DescriptorImpl getDescriptor() {
    return new DescriptorImpl();
  }

  /**
   * Descriptor for {@link DatadogBuildListener}. Used as a singleton.
   * The class is marked as public so that it can be accessed from views.
   *
   * <p>See <tt>DatadogBuildListener/*.jelly</tt> for the actual HTML fragment
   * for the configuration screen.
   */
  @Extension // Indicates to Jenkins that this is an extension point implementation.
  public static final class DescriptorImpl extends Descriptor<DatadogBuildListener> {
    /**
     * Persist global configuration information by storing in a field and
     * calling save().
     */
    private String apiKey;

    /**
     * Runs when the {@link DescriptorImpl} class is created.
     */
    public DescriptorImpl() {
      load(); // load the persisted global configuration
    }

    /**
     * Tests the {@link apiKey} from the configuration screen, to check its' validity.
     *
     * @param formApiKey - A String containing the apiKey submitted from the form on the
     *                     configuration screen, which will be used to authenticate a request to the
     *                     Datadog API.
     * @return a FormValidation object used to display a message to the user on the configuration
     *         screen.
     * @throws IOException if there is an input/output exception.
     * @throws ServletException if there is a servlet exception.
     */
    public FormValidation doTestConnection(@QueryParameter("apiKey") final String formApiKey)
        throws IOException, ServletException {
      String urlParameters = "?api_key=" + formApiKey;
      HttpURLConnection conn = null;

      try {
        // Make request
        URL url = new URL(DatadogBuildListener.BASEURL + DatadogBuildListener.VALIDATE
                          + urlParameters);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        // Get response
        BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) {
          result.append(line);
        }
        rd.close();

        // Validate
        JSONObject json = (JSONObject) JSONSerializer.toJSON( result.toString() );
        if ( json.getBoolean("valid") ) {
          return FormValidation.ok("Great! Your API key is valid.");
        } else {
          return FormValidation.error("Hmmm, your API key seems to be invalid.");
        }
      } catch (Exception e) {
        if ( conn.getResponseCode() == DatadogBuildListener.HTTP_FORBIDDEN ) {
          return FormValidation.error("Hmmm, your API key may to be invalid. "
                                      + "We received a 403 error.");
        }
        return FormValidation.error("Client error: " + e);
      } finally {
        if (conn != null) {
          conn.disconnect();
        }
      }
    }

    /**
     * Indicates if this builder can be used with all kinds of project types.
     *
     * @param aClass - An extension of the AbstractProject class representing a specific type of
     *                 project.
     * @return a boolean signifying whether or not a builder can be used with a specific type of
     *         project.
     */
    public boolean isApplicable(final Class<? extends AbstractProject> aClass) {
      return true;
    }

    /**
     * Getter function for a human readable plugin name, used in the configuration screen.
     *
     * @return a String containing the human readable display name for this plugin.
     */
    public String getDisplayName() {
      return DatadogBuildListener.DISPLAY_NAME;
    }

    /**
     * Indicates if this builder can be used with all kinds of project types.
     *
     * @param req - A StaplerRequest object
     * @param formData - A JSONObject containing the submitted form data from the configuration
     *                   screen.
     * @return a boolean signifying the success or failure of configuration.
     * @throws FormException if the formData is invalid.
     */
    @Override
    public boolean configure(final StaplerRequest req, final JSONObject formData)
           throws FormException {
      apiKey = formData.getString("apiKey");
      save(); // persist global configuration information
      return super.configure(req, formData);
    }

    /**
     * Getter function for the {@link apiKey} global configuration.
     *
     * @return a String containing the {@link apiKey} global configuration.
     */
    public String getApiKey() {
      return apiKey;
    }
  }
}

