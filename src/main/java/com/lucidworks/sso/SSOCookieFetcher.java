package com.lucidworks.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.rholder.retry.Attempt;
import com.github.rholder.retry.AttemptTimeLimiters;
import com.github.rholder.retry.RetryListener;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.machinepublishers.jbrowserdriver.ProxyConfig;
import com.machinepublishers.jbrowserdriver.Settings;
import com.machinepublishers.jbrowserdriver.Timezone;
import com.machinepublishers.jbrowserdriver.UserAgent;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Fetch cookies from a generic website after performing some "login steps"
 * <p>
 * Example input format: https://raw.githubusercontent.com/nddipiazza/SSO-Cookie-Fetcher/master/input.json
 * <p>
 * It will print all logging info to stderr
 * <p>
 * It will print the cookies in format cookieName;;;cookieValue to the stdout.
 * <p>
 * See https://raw.githubusercontent.com/nddipiazza/SSO-Cookie-Fetcher/master/src/main/java/com/lucidworks/sso
 * /CallSSOCookieFetcher.java
 * <p>
 * For an example of how to call this from a Java class.
 */
public class SSOCookieFetcher {
  private static ObjectMapper MAPPER = new ObjectMapper();

  @Option(name = "--url", required = true, usage = "Starting URL")
  private String url = null;

  @Option(name = "--proxy", usage = "Proxy server in <host>:<args> format")
  private String proxy = null;

  @Option(name = "--screenshots", usage = "Save screenshots of each web form as sso process proceeds")
  private boolean takeScreenshots = false;

  @Option(name = "--stopAfterAttempt", usage = "Stop retrying to get sso cookies after failed attempt")
  private int stopAfterAttempt = 5;

  @Option(name = "--attemptTimeLimiter", usage = "Limit attempts to this many seconds")
  private int attemptTimeLimiter = 5 * 60;

  @Option(name = "--socketTimeout", usage = "Jbrowserdriver's socket timeout parameter.")
  private int socketTimeout = 10000;

  @Option(name = "--ajaxWait", usage = "Jbrowserdriver's ajax wait parameter.")
  private long ajaxWait = 10000L;

  @Option(name = "--screenWidth", usage = "Jbrowserdriver's browser window screen width.")
  private int screenWidth = 800;

  @Option(name = "--screenHeight", usage = "Jbrowserdriver's browser window screen height.")
  private int screenHeight = 800;

  public static void main(String[] args) throws Exception {
    new SSOCookieFetcher().runProcess(args);
  }

  private void runProcess(String[] args) throws Exception {

    CmdLineParser parser = new CmdLineParser(this);

    parser.setUsageWidth(80);

    parser.parseArgument(args);

    JsonNode stepsJson = MAPPER.readTree(System.in);

    Retryer retryer = RetryerBuilder.newBuilder()
        .withStopStrategy(StopStrategies.stopAfterAttempt(stopAfterAttempt))
        .withAttemptTimeLimiter(AttemptTimeLimiters.fixedTimeLimit(attemptTimeLimiter, TimeUnit.SECONDS))
        .retryIfException()
        .retryIfRuntimeException()
        .withRetryListener(new RetryListener() {
          @Override
          public <V> void onRetry(Attempt<V> attempt) {
            if (attempt.hasException()) {
              System.err.println("Error occurred during attempt");
              attempt.getExceptionCause().printStackTrace(System.err);
            }
          }
        }).build();
    retryer.call(() -> {
      doSteps(stepsJson);
      return true;
    });
  }

  private void doSteps(JsonNode stepsJson) throws Exception {
    Settings.Builder settingsBuilder = Settings.builder();
    settingsBuilder.timezone(Timezone.AMERICA_NEWYORK);

    if (proxy != null) {
      String httpHost;
      Integer httpPort;
      String errorMessage = "Invalid proxy: '" + proxy +
          "'; Must specify proxy as \"<host>:<port>\" format";
      String[] mainProxyParts = proxy.split(",");
      if (mainProxyParts.length > 2) {
        throw new Exception(errorMessage);
      }
      for (String mainProxyPart : mainProxyParts) {
        String[] proxyParts = mainProxyPart.replace("http://",
            "").split(":");
        if (proxyParts.length != 2) {
          throw new Exception(errorMessage);
        }
        httpHost = proxyParts[0];
        try {
          httpPort = Integer.parseInt(proxyParts[1]);
        } catch (NumberFormatException e) {
          throw new Exception("Invalid proxy port: '" + proxyParts[1] + "'", e);
        }
        if (httpHost != null && httpPort != null) {
          //System.out.println("Using proxy host = " + httpHost + ", " + httpPort);
          settingsBuilder.proxy(new ProxyConfig(ProxyConfig.Type.HTTP, httpHost, httpPort));
        }
      }
    }

    settingsBuilder.ajaxWait(ajaxWait);
    settingsBuilder.socketTimeout(socketTimeout);
    settingsBuilder.blockAds(true);
    settingsBuilder.quickRender(true);
    settingsBuilder.userAgent(UserAgent.CHROME);
    JBrowserDriver driver = new JBrowserDriver(settingsBuilder.build());

    try {
      driver.get(url);

      System.err.println("Loaded " + url);

      driver.manage().window().setSize(new Dimension(screenWidth, screenHeight));

      new WebDriverWait(driver, ajaxWait / 1000, 250).until(wd -> ((JavascriptExecutor) wd).executeScript("return " +
          "document.readyState").equals("complete"));
      int screenshotIndex = 0;
      for (JsonNode nextStep : stepsJson) {
        ++screenshotIndex;
        if (nextStep.has("inputFields")) {
          for (JsonNode inputField : nextStep.get("inputFields")) {
            String text = inputField.get("value").asText();
            if (StringUtils.startsWith(text, "$$PASSWORD$$")) {
              String passwordEnv = System.getenv(text.substring("$$PASSWORD$$".length()));
              if (StringUtils.isNotEmpty(passwordEnv)) {
                text = passwordEnv;
              } else {
                Console console = System.console();
                if (console == null) {
                  System.err.println("Cannot get password and console is null");
                  System.exit(1);
                }
                char passwordArray[] = console.readPassword("Enter password: ");
                text = new String(passwordArray);
              }
            }
            String nextXpath = inputField.get("xpath").asText();
            System.err.println("Entering field " + nextXpath);
            driver.findElement(By.xpath(nextXpath)).clear();
            Thread.sleep(1500L);
            driver.findElement(By.xpath(nextXpath)).sendKeys(text);
            Thread.sleep(1500L);
          }
        }
        if (nextStep.has("submitButtonXPath")) {
          if (takeScreenshots) {
            byte[] screenshot = driver.getScreenshotAs(OutputType.BYTES);
            Files.write(Paths.get("screenshot-beforeclick-" + screenshotIndex + ".png"), screenshot);
          }
          System.err.println("Submitting button " + nextStep.get("submitButtonXPath").asText());
          driver.findElement(By.xpath(nextStep.get("submitButtonXPath").asText())).click();
          new WebDriverWait(driver, ajaxWait / 1000, 250).until(wd -> ((JavascriptExecutor) wd).executeScript("return document" +
              ".readyState").equals("complete"));
          if (takeScreenshots) {
            byte[] screenshot = driver.getScreenshotAs(OutputType.BYTES);
            Files.write(Paths.get("screenshot-afterclick-" + screenshotIndex + ".png"), screenshot);
          }
        } else {
          System.err.println("Need a submit button for step " + nextStep.toString());
          System.exit(1);
        }
      }
      for (Cookie c : driver.manage().getCookies()) {
        System.out.println(c.getName() + ";;;" + c.getValue());
        System.err.println("Got cookie: " + c.getName());
      }
    } finally {
      System.err.println("Quitting jbrowserdriver process...");
      driver.quit();
    }
    System.err.println("Success! Exiting with status code = 0.");
    System.exit(0);
  }
}
