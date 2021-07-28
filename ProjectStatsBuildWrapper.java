package io.jenkins.plugins.sample;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Stack;
import java.lang.StringBuilder;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.Runtime;
import java.util.Scanner;
import java.util.ArrayList;


public class ProjectStatsBuildWrapper extends BuildWrapper {

    private static final String REPORT_TEMPLATE_PATH = "/stat.txt";
    private static final String PROJECT_NAME_VAR = "$PROJECT_NAME$";
    private static final String CLASSES_NUMBER_VAR = "$CLASSES_NUMBER$";
    private static final String LINES_NUMBER_VAR = "$LINES_NUMBER$";

    @DataBoundConstructor
    public ProjectStatsBuildWrapper() {
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener) {
        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
              throws IOException, InterruptedException
            {
                ProjectStats stats = buildStats(build, build.getWorkspace(), listener);
                String report = generateReport(build.getProject().getDisplayName(), stats, listener);
                File artifactsDir = build.getArtifactsDir();
                if (!artifactsDir.isDirectory()) {
                    boolean success = artifactsDir.mkdirs();
                    if (!success) {
                        listener.getLogger().println("Can't create artifacts directory at "
                          + artifactsDir.getAbsolutePath());
                    }
                }
                String path = artifactsDir.getCanonicalPath() + REPORT_TEMPLATE_PATH;
                listener.getLogger().println(report);
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path),
                  StandardCharsets.UTF_8))) {
                    writer.write(report);
                }

                String command = "find";
                listener.getLogger().println(command);
                Process p4 = Runtime.getRuntime().exec(command, null, new File(build.getWorkspace().toString()));
                String[] paths = printResults(p4).split("\n");
                listener.getLogger().println("Working within workspace: " + build.getWorkspace().toString());
                ArrayList<String> files = new ArrayList<String>();
                String logTemp = "";
                for(String temp: paths){
                    if(path.contains("testCases")){

                    }else if(temp.contains(".java")){
                        listener.getLogger().print("Found file: " + temp);
                        File f = new File(build.getWorkspace().toString() + "/" + temp.substring(2));
                        Scanner sc = new Scanner(f);
                        int count = 0;
                        while(sc.hasNextLine()){
                            String text = sc.nextLine();
                            count++;
                        }
                        listener.getLogger().println(" with " + count + " lines. ");
                        temp = temp.split("./src/")[1];
                        temp = temp.split(".java")[0];
                        files.add(temp);
                        sc.close();
                        
                    }
                }
                File fLogs = new File(build.getWorkspace().toString() + "/.git/logs/HEAD");
                Scanner scanLogs = new Scanner(fLogs);
                while(scanLogs.hasNextLine()){
                    logTemp = logTemp + scanLogs.nextLine() + "\n";
                }
                scanLogs.close();
                ArrayList<String> logs = new ArrayList<String>();
                String[] logTemps = logTemp.split("\n");
                for(int i = 0; i<logTemps.length; i++){
                    logs.add(logTemps[i].split(" ")[0]);
                }
                //listener.getLogger().println(id0 + ", " + id1);
                command = "mkdir testCases";
                Process pmkdir = Runtime.getRuntime().exec(command, null, new File(build.getWorkspace().toString()));

                String[] counts = {"public", "for", "while", "if"};
                for(String file: files){
                    String fileString = build.getWorkspace().toString() + "/testCases/" + file + "Tests.csv";
                    listener.getLogger().println(fileString);
                    File testCases = new File(fileString);
                    //testCases.createNewFile();
                    String header = "id, LOC, " + counts[0] + ", " + counts[1] + ", " + counts[2] + ", " + counts[3];
                    PrintWriter pw = new PrintWriter(testCases);
                    pw.println(header);
                    for(int i = logs.size() - 2; i>0; i--){
                        command = "git show " + logs.get(i+1) + ":src/" + file + ".java";
                        //listener.getLogger().println(command);
                        Process p5 = Runtime.getRuntime().exec(command, null, new File(build.getWorkspace().toString()));
                        String oldProgram = printResults(p5);

                        command = "git show " + logs.get(i) + ":src/" + file + ".java";
                        //listener.getLogger().println(command);
                        Process p6 = Runtime.getRuntime().exec(command, null, new File(build.getWorkspace().toString()));
                        String currentProgram = printResults(p6);
                        int[] c = changeAnalysis(counts, oldProgram, currentProgram);
                        
                        String line = logs.get(i) + ", " + countLines(currentProgram) + ", " + c[0] + ", " + c[1] + ", " + c[2] + ", " + c[3];
                        pw.println(line);
                    }
                    pw.close();
                }

                return super.tearDown(build, listener);
            }
        };
    }

    public static String printResults(Process process) throws IOException{
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line = "";
        String text = "";
        while((line = reader.readLine()) != null){
            text = text + line + "\n";
        }
        return text;
    }

    public static int countLines(String program){
        return program.split("\n").length;
    }

    public static int countInstances(String str, String sub){
        int lastIndex = 0; 
        int count = 0; 
        while(lastIndex != -1){
            lastIndex = str.indexOf(sub, lastIndex);
            if(lastIndex!=-1){
                count++;
                lastIndex+=sub.length();
            }
        }
        return count;
    }

    public static int[] analysis(String[] counts, String program){
        int[] c = new int[4];
        for (int i = 0; i< 4; i++){
            c[i] = countInstances(program, counts[i]);
        }
        return c;
    }

    public static int[] changeAnalysis(String[] counts, String old, String changed){
        int[] cOld = analysis(counts, old);
        int[] cNew = analysis(counts, changed);
        int[] c = new int[4];
        for (int i =0; i< 4; i++){
            c[i] = cNew[i] - cOld[i];
        }
        return c;
    }

    private static ProjectStats buildStats(AbstractBuild build, FilePath root, BuildListener listener) throws IOException, InterruptedException {
        int classesNumber = 0;
        int linesNumber = 0;
        Stack<FilePath> toProcess = new Stack<>();
        toProcess.push(root);
        while (!toProcess.isEmpty()) {
            FilePath path = toProcess.pop();
            if (path.isDirectory()) {
                toProcess.addAll(path.list());
            } else if (path.getName().endsWith(".java")) {
                classesNumber++;
                linesNumber += countLines(path);
                listener.getLogger().print("Classes number: ");
                listener.getLogger().println(classesNumber);
		        listener.getLogger().print("Current line number: ");
                listener.getLogger().println(linesNumber);

            }
        }
        String text = "Classes number: " + classesNumber + ", Lines number: " + linesNumber;
        try{
            //File f = new File("")
            File artDir = build.getArtifactsDir();
            String path = artDir.getCanonicalPath() + "/stats3.txt";
            //File file = new File(path);
            FileWriter f = new FileWriter(path);
            f.write(text);
            f.close();
        }catch(IOException e){

        }
        return new ProjectStats(classesNumber, linesNumber);
    }

    private static int countLines(FilePath path) throws IOException, InterruptedException {
        byte[] buffer = new byte[1024];
        int result = 1;
        try (InputStream in = path.read()) {
            while (true) {
                int read = in.read(buffer);
                if (read < 0) {
                    return result;
                }
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        result++;
                    }
                }
            }
        }
    }

    private static String generateReport(String projectName, ProjectStats stats, BuildListener listener) throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        
        try{
            BufferedReader in = new BufferedReader(new FileReader("./stats.txt"));
            String s;
            while((s = in.readLine()) != null){
                contentBuilder.append(s);
            }
            in.close();
        }catch(IOException e){
        }

        String content = contentBuilder.toString();
        String text = projectName + ", Classes: " + stats.getClassesNumber() + ", Lines: " + stats.getLinesNumber();
        listener.getLogger().println(content);
        /*content = content.replace(PROJECT_NAME_VAR, projectName);
        content = content.replace(CLASSES_NUMBER_VAR, String.valueOf(stats.getClassesNumber()));
        content = content.replace(LINES_NUMBER_VAR, String.valueOf(stats.getLinesNumber()));*/
        listener.getLogger().println(content);
        
        return text;
    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Stats.txt file with Classes/Lines number for Java program(s)";
        }

    }

}
