package com.lucidworks.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SSOCookieFetcher {
  private static ObjectMapper MAPPER = new ObjectMapper();

  @Option(name = "--url", required = true, usage = "Starting URL")
  private String url = null;

  @Option(name = "--stepsPath", required = true, usage = "Steps json file path")
  private String stepsJsonFile = null;

  @Option(name = "--proxy", usage = "Proxy server in <host>:<args> format")
  private String proxy = null;

  @Option(name = "--screenshots", usage = "Save screenshots of each web form as sso process proceeds")
  private boolean takeScreenshots = false;


  public static void main(String[] args) throws Exception {
    new SSOCookieFetcher().runProcess(args);
  }

  private void runProcess(String[] args) throws Exception {

    CmdLineParser parser = new CmdLineParser(this);

    parser.setUsageWidth(80);

    parser.parseArgument(args);

    int retryCount = 6;
    while (retryCount-- > 0) {
      try {
        doSteps();
        break;
      } catch (Exception e) {
        System.out.println("Failed due to exception. Retries remaining " + retryCount);
        e.printStackTrace();
      }
    }
  }

  private void doSteps() throws Exception {
    JsonNode stepsJson = MAPPER.readTree(new File(stepsJsonFile));

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
          System.out.println("Using proxy host = " + httpHost + ", " + httpPort);
          settingsBuilder.proxy(new ProxyConfig(ProxyConfig.Type.HTTP, httpHost, httpPort));
        }
      }
    }

    settingsBuilder.ajaxWait(30000L);
    settingsBuilder.socketTimeout(30000);
    settingsBuilder.blockAds(false);
    settingsBuilder.quickRender(false);
    settingsBuilder.userAgent(UserAgent.CHROME);

    JBrowserDriver driver = new JBrowserDriver(settingsBuilder.build());

    try {
      driver.get(url);

      driver.manage().window().setSize(new Dimension(800, 800));

      new WebDriverWait(driver, 60, 250).until(wd -> ((JavascriptExecutor) wd).executeScript("return document.readyState").equals("complete"));

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
                char passwordArray[] = console.readPassword("Enter password: ");
                text = new String(passwordArray);
              }
            }
            driver.findElement(By.xpath(inputField.get("xpath").asText())).clear();
            Thread.sleep(1500L);
            driver.findElement(By.xpath(inputField.get("xpath").asText())).sendKeys(text);
            Thread.sleep(1500L);
          }
        }
        if (nextStep.has("submitButtonXPath")) {

          if (takeScreenshots) {
            byte[] screenshot = driver.getScreenshotAs(OutputType.BYTES);
            Files.write(Paths.get("screenshot-beforeclick-" + screenshotIndex + ".png"), screenshot);
          }

          driver.findElement(By.xpath(nextStep.get("submitButtonXPath").asText())).click();
          new WebDriverWait(driver, 60, 250).until(wd -> ((JavascriptExecutor) wd).executeScript("return document.readyState").equals("complete"));

          if (takeScreenshots) {
            byte[] screenshot = driver.getScreenshotAs(OutputType.BYTES);
            Files.write(Paths.get("screenshot-afterclick-" + screenshotIndex + ".png"), screenshot);
          }

        } else {
          throw new Exception("Need a submit button for step " + nextStep.toString());
        }
      }
      for (Cookie c : driver.manage().getCookies()) {
        System.out.println(c.getName() + ";;;" + c.getValue());
      }
    } finally {
      driver.quit();
    }
  }
}
