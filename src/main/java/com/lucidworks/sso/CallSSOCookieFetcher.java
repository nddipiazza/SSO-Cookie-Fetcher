package com.lucidworks.sso;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class CallSSOCookieFetcher {
  @Option(name = "--javaBin", usage = "The java executable path.")
  private String java = "java";

  @Option(name = "--jarFile", required = true, usage = "The SSOCookieFetcherJarFile")
  private String jarFile;

  public static void main(String [] args) throws Exception {
    new CallSSOCookieFetcher().doMain(args);
  }

  public void doMain(String [] args) throws Exception {
    CmdLineParser parser = new CmdLineParser(this);

    parser.setUsageWidth(80);

    parser.parseArgument(args);

    ProcessBuilder builder = new ProcessBuilder(java, "-jar", jarFile, "--url", "https://fudgeunlimited.sharepoint.com/");
    builder.environment().put("password", System.getenv("password"));
    Process process = builder.start();

    OutputStream stdin = process.getOutputStream();
    InputStream stderr = process.getErrorStream();
    InputStream stdout = process.getInputStream();

    final List<String> stdoutList = new ArrayList<>();

    new Thread(() -> {
      Scanner scanner = new Scanner(stdout);
      while (scanner.hasNextLine()) {
        System.out.println("STDOUT: " + scanner.nextLine());
        stdoutList.add(scanner.nextLine());
      }
      scanner.close();
    }).start();

    new Thread(() -> {
      Scanner scanner = new Scanner(stderr);
      while (scanner.hasNextLine()) {
        System.out.println("STDERR: " + scanner.nextLine());
      }
      scanner.close();
    }).start();

    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin));

    writer.write(FileUtils.readFileToString(new File("input.json")));
    writer.flush();

    int exitCode = process.waitFor();

    if (exitCode != 0) {
      throw new Exception("Could not get generic sso cookies. Check previous logs");
    }

    System.out.println("Cookies");
    for (String cookie : stdoutList) {
      System.out.println(cookie);
    }
  }
}
