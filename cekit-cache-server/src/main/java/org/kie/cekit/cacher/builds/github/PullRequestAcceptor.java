package org.kie.cekit.cacher.builds.github;

import org.kie.cekit.cacher.builds.yaml.YamlFilesHelper;
import org.kie.cekit.cacher.builds.yaml.pojo.Modules;
import org.kie.cekit.cacher.objects.PlainArtifact;
import org.kie.cekit.cacher.properties.CacherProperties;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * this class holds all operations related with rhdm/rhpam files changes
 */
@ApplicationScoped
public class PullRequestAcceptor implements BuildDateUpdatesInterceptor {

    private Logger log = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    private Map<String, PlainArtifact> elements = new HashMap<>();

    @Inject
    GitRepository gitRepository;

    @Inject
    CacherProperties cacherProperties;

    @Inject
    YamlFilesHelper yamlFilesHelper;

    @Inject
    PullRequestSender pullRequestSender;

    /**
     * {@link BuildDateUpdatesInterceptor}
     *
     * @param artifact
     */
    @Override
    public void onNewBuildReceived(PlainArtifact artifact) {
        try {
            LocalDate upstreamBuildDate = LocalDate.parse(gitRepository.getCurrentProductBuildDate(), formatter);
            LocalDate buildDate = LocalDate.parse(artifact.getBuildDate(), formatter);

            if (buildDate.isAfter(upstreamBuildDate)) {
                log.fine("File " + artifact.getFileName() + " received for PR.");
                elements.put(artifact.getFileName(), artifact);
            } else {
                log.fine(String.format("BuildDate received [%s] is before or equal than the upstream build date [%s]", buildDate, upstreamBuildDate));
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * {@link BuildDateUpdatesInterceptor}
     *
     * @param fileName
     * @param checkSum
     */
    @Override
    public void onFilePersisted(String fileName, String checkSum) {

        try {

            if (elements.containsKey(fileName)) {
                log.fine("File received for pull request " + fileName);
                elements.get(fileName).setChecksum(checkSum);

                if (isRhpamReadyForPR()) {
                    log.info("RHPAM is Ready to perform a Pull Request.");

                    // create a new branch
                    // only if all needed files are ready this step will be executed, any file is ok to retrieve
                    // the build date.
                    String buildDate = elements.get(fileName).getBuildDate();
                    gitRepository.handleBranch(BranchOperation.NEW_BRANCH, buildDate, "rhpam-7-image");

                    String bcMonitoringFile = cacherProperties.getGitDir() + "/rhpam-7-image/businesscentral-monitoring/modules/businesscentral-monitoring/module.yaml";
                    Modules bcMonitoring = yamlFilesHelper.load(bcMonitoringFile);

                    String businessCentralFile = cacherProperties.getGitDir() + "/rhpam-7-image/businesscentral/modules/businesscentral/module.yaml";
                    Modules businessCentral = yamlFilesHelper.load(businessCentralFile);

                    String controllerFile = cacherProperties.getGitDir() + "/rhpam-7-image/controller/modules/controller/module.yaml";
                    Modules controller = yamlFilesHelper.load(controllerFile);

                    String kieserverFile = cacherProperties.getGitDir() + "/rhpam-7-image/kieserver/modules/kieserver/module.yaml";
                    Modules kieserver = yamlFilesHelper.load(kieserverFile);

                    String smartrouterFile = cacherProperties.getGitDir() + "/rhpam-7-image/smartrouter/modules/smartrouter/module.yaml";
                    Modules smartrouter = yamlFilesHelper.load(smartrouterFile);

                    // Prepare Business Central Monitoring Changes
                    bcMonitoring.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("BUSINESS_CENTRAL_MONITORING_DISTRIBUTION_ZIP")) {
                            String bcMonitoringFileName = String.format("rhpam-%s.PAM-redhat-%s-monitoring-ee7.zip", cacherProperties.version(), buildDate);
                            String bcMonitoringCheckSum;
                            try {
                                bcMonitoringCheckSum = elements.get(bcMonitoringFileName).getChecksum();

                                log.fine(String.format("Updating BC monitoring from [%s] to [%s]", artifact.getMd5(), bcMonitoringCheckSum));
                                artifact.setMd5(bcMonitoringCheckSum);
                                yamlFilesHelper.writeModule(bcMonitoring, bcMonitoringFile);

                                // find target: "business_central_monitoring_distribution.zip"
                                // and add comment on next line : rhpam-7.5.0.PAM-redhat-${buildDate}-monitoring-ee7.zip
                                reAddComment(bcMonitoringFile, "target: \"business_central_monitoring_distribution.zip\"",
                                        String.format("  # %s", bcMonitoringFileName));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    // Prepare Business Central Changes
                    businessCentral.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("BUSINESS_CENTRAL_DISTRIBUTION_ZIP")) {
                            String bcFileName = String.format("rhpam-%s.PAM-redhat-%s-business-central-eap7-deployable.zip", cacherProperties.version(), buildDate);
                            String bcCheckSum;
                            try {
                                bcCheckSum = elements.get(bcFileName).getChecksum();

                                log.fine(String.format("Updating Business Central from [%s] to [%s]", artifact.getMd5(), bcCheckSum));
                                artifact.setMd5(bcCheckSum);
                                yamlFilesHelper.writeModule(businessCentral, businessCentralFile);

                                // find target: "business_central_distribution.zip"
                                // and add comment on next line : rhpam-7.5.0.PAM-redhat-${buildDate}-business-central-eap7-deployable.zip
                                reAddComment(businessCentralFile, "target: \"business_central_distribution.zip\"",
                                        String.format("  # %s", bcFileName));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    // Prepare controller Changes
                    controller.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("ADD_ONS_DISTRIBUTION_ZIP")) {
                            String controllerFileName = String.format("rhpam-%s.PAM-redhat-%s-add-ons.zip", cacherProperties.version(), buildDate);
                            String controllerCheckSum;
                            try {
                                controllerCheckSum = elements.get(controllerFileName).getChecksum();

                                log.fine(String.format("Updating RHPAM Controller from [%s] to [%s]", artifact.getMd5(), controllerCheckSum));
                                artifact.setMd5(controllerCheckSum);
                                yamlFilesHelper.writeModule(controller, controllerFile);

                                // find target: "add_ons_distribution.zip"
                                // and add comment on next line :  rhpam-7.5.0.PAM-redhat-${buildDate}-add-ons.zip
                                reAddComment(controllerFile, "target: \"add_ons_distribution.zip\"",
                                        String.format("  # %s", controllerFileName));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    // Prepare kieserver changes, jbpm-wb-kie-server-backend file
                    String kieServerFileName = String.format("rhpam-%s.PAM-redhat-%s-kie-server-ee8.zip", cacherProperties.version(), buildDate);
                    String backendFileName = String.format("jbpm-wb-kie-server-backend-%s.redhat-%s.jar", cacherProperties.version(), buildDate);
                    kieserver.getEnvs().stream().forEach(env -> {
                        if (env.getName().equals("JBPM_WB_KIE_SERVER_BACKEND_JAR")) {

                            log.fine(String.format("Update jbpm-wb-kie-server-backend file from [%s] to [%s]", env.getValue(), backendFileName));
                            env.setValue(backendFileName);
                            yamlFilesHelper.writeModule(kieserver, kieserverFile);

                        }
                    });
                    kieserver.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("KIE_SERVER_DISTRIBUTION_ZIP")) {

                            String kieServerCheckSum;
                            try {
                                kieServerCheckSum = elements.get(kieServerFileName).getChecksum();

                                log.fine(String.format("Updating RHPAM kieserver KIE_SERVER_DISTRIBUTION_ZIP from [%s] to [%s]", artifact.getMd5(), kieServerCheckSum));
                                artifact.setMd5(kieServerCheckSum);
                                yamlFilesHelper.writeModule(kieserver, kieserverFile);


                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        if (artifact.getName().equals("BUSINESS_CENTRAL_DISTRIBUTION_ZIP")) {
                            String bcFileName = String.format("rhpam-%s.PAM-redhat-%s-business-central-eap7-deployable.zip", cacherProperties.version(), buildDate);
                            String bcCheckSum;
                            try {
                                bcCheckSum = elements.get(bcFileName).getChecksum();

                                log.fine(String.format("Updating RHPAM kieserver BUSINESS_CENTRAL_DISTRIBUTION_ZIP from [%s] to [%s]", artifact.getMd5(), bcCheckSum));
                                artifact.setMd5(bcCheckSum);
                                yamlFilesHelper.writeModule(kieserver, kieserverFile);

                                // Only add comments when the last write operation will be made.
                                // find target: "business_central_distribution.zip"
                                // and add comment on next line :  rhpam-7.5.0.PAM-redhat-${buildDate}-business-central-eap7-deployable.zip
                                reAddComment(kieserverFile, "target: \"business_central_distribution.zip\"",
                                        String.format("  # %s", bcFileName));

                                // find target: "kie_server_distribution.zip"
                                // and add comment on next line :  rhpam-7.5.0.PAM-redhat-${buildDate}-kie-server-ee8.zip
                                reAddComment(kieserverFile, "target: \"kie_server_distribution.zip\"",
                                        String.format("  # %s", kieServerFileName));

                                // find target: "slf4j-simple.jar"
                                // and add comment on next line :  slf4j-simple-1.7.22.redhat-2.jar
                                reAddComment(kieserverFile, "target: \"slf4j-simple.jar\"", "  # slf4j-simple-1.7.22.redhat-2.jar");

                                // find target: "jbpm-wb-kie-server-backend-7.5.0.redhat-X.jar"
                                // and add comment on next line : # remember to also update "JBPM_WB_KIE_SERVER_BACKEND_JAR" value
                                reAddComment(kieserverFile, String.format("  value: \"%s\"", backendFileName),
                                        "# remember to also update \"JBPM_WB_KIE_SERVER_BACKEND_JAR\" value");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    // Prepare smartrouter changes
                    smartrouter.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("ADD_ONS_DISTRIBUTION_ZIP")) {
                            String smartrouterFileName = String.format("rhpam-%s.PAM-redhat-%s-add-ons.zip", cacherProperties.version(), buildDate);
                            String smartrouterCheckSum;
                            try {
                                smartrouterCheckSum = elements.get(smartrouterFileName).getChecksum();

                                log.fine(String.format("Updating RHPAM smartrouter ADD_ONS_DISTRIBUTION_ZIP from [%s] to [%s]", artifact.getMd5(), smartrouterCheckSum));
                                artifact.setMd5(smartrouterCheckSum);
                                yamlFilesHelper.writeModule(smartrouter, smartrouterFile);

                                // find target: "business_central_distribution.zip"
                                // and add comment on next line :  rhpam-7.5.0.PAM-redhat-${buildDate}-add-ons.zip
                                reAddComment(smartrouterFile, "target: \"add_ons_distribution.zip\"",
                                        String.format("  # %s", smartrouterFileName));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    if (gitRepository.addChanges("rhpam-7-image") && gitRepository.commitChanges("rhpam-7-image", buildDate, "Applying RHPAM nightly build for build date " + buildDate)) {
                        log.fine("About to send Pull Request on rhpam-7-image git repository on branch " + buildDate);

                        String prTittle = "Updating RHPAM artifacts based on the latest nightly build " + buildDate;
                        String prDescription = "This PR was created automatically, please review carefully before merge, the" +
                                " build date is " + buildDate;
                        pullRequestSender.performPullRequest("rhpam-7-image", buildDate, prTittle, prDescription);

                        gitRepository.handleBranch(BranchOperation.DELETE_BRANCH, buildDate, "rhpam-7-image");

                    } else {
                        log.warning("something went wrong while preparing the rhpam-7-image for the pull request");
                    }

                    // remove rhpam from element items
                    removeItems("rhpam");

                }

                if (isRhdmReadyForPR()) {
                    log.info("RHDM is Ready to perform a Pull Request.");

                    // create a new branch
                    // only if all needed files are ready this step will be executed, any file is ok to retrieve
                    // the build date.
                    String buildDate = elements.get(fileName).getBuildDate();
                    gitRepository.handleBranch(BranchOperation.NEW_BRANCH, buildDate, "rhdm-7-image");

                    // load all required files:
                    String controllerFile = cacherProperties.getGitDir() + "/rhdm-7-image/controller/modules/controller/module.yaml";
                    Modules controller = yamlFilesHelper.load(controllerFile);

                    String decisionCentralFile = cacherProperties.getGitDir() + "/rhdm-7-image/decisioncentral/modules/decisioncentral/module.yaml";
                    Modules decisionCentral = yamlFilesHelper.load(decisionCentralFile);

                    String kieserverFile = cacherProperties.getGitDir() + "/rhdm-7-image/kieserver/modules/kieserver/module.yaml";
                    Modules kieserver = yamlFilesHelper.load(kieserverFile);

                    String optawebFile = cacherProperties.getGitDir() + "/rhdm-7-image/optaweb-employee-rostering/modules/optaweb-employee-rostering/module.yaml";
                    Modules optaweb = yamlFilesHelper.load(optawebFile);

                    // Prepare controller Changes
                    controller.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("ADD_ONS_DISTRIBUTION_ZIP")) {
                            String controllerFileName = String.format("rhdm-%s.DM-redhat-%s-add-ons.zip", cacherProperties.version(), buildDate);
                            String controllerCheckSum;
                            try {
                                controllerCheckSum = elements.get(controllerFileName).getChecksum();

                                log.fine(String.format("Updating RHDM Controller from [%s] to [%s]", artifact.getMd5(), controllerCheckSum));
                                artifact.setMd5(controllerCheckSum);
                                yamlFilesHelper.writeModule(controller, controllerFile);

                                // find target: "add_ons_distribution.zip"
                                // and add comment on next line :  rhdm-7.5.0.DM-redhat-${buildDate}-add-ons.zip
                                reAddComment(controllerFile, "target: \"add_ons_distribution.zip\"",
                                        String.format("  # %s", controllerFileName));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    // Prepare Decision Central changes
                    decisionCentral.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("DECISION_CENTRAL_DISTRIBUTION_ZIP")) {
                            String decisionCentralFileName = String.format("rhdm-%s.DM-redhat-%s-decision-central-eap7-deployable.zip", cacherProperties.version(), buildDate);
                            try {
                                String decisionCentralCheckSum = elements.get(decisionCentralFileName).getChecksum();

                                log.fine(String.format("Updating RHDM Decision Central from [%s] to [%s]", artifact.getMd5(), decisionCentralCheckSum));
                                artifact.setMd5(decisionCentralCheckSum);
                                yamlFilesHelper.writeModule(decisionCentral, decisionCentralFile);

                                // find target: "decision_central_distribution.zip"
                                // and add comment on next line :  rhdm-7.5.0.DM-redhat-${buildDate}-decision-central-eap7-deployable.zip
                                reAddComment(decisionCentralFile, "target: \"decision_central_distribution.zip\"",
                                        String.format("  # %s", decisionCentralFileName));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                    });

                    // Prepare kieserver changes
                    kieserver.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("KIE_SERVER_DISTRIBUTION_ZIP")) {
                            String kieserverFileName = String.format("rhdm-%s.DM-redhat-%s-kie-server-ee8.zip", cacherProperties.version(), buildDate);
                            String kieserverCheckSum;
                            try {
                                kieserverCheckSum = elements.get(kieserverFileName).getChecksum();

                                log.fine(String.format("Updating RHDM Decision Central from [%s] to [%s]", artifact.getMd5(), kieserverCheckSum));
                                artifact.setMd5(kieserverCheckSum);
                                yamlFilesHelper.writeModule(kieserver, kieserverFile);

                                // find target: "kie_server_distribution.zip"
                                // and add comment on next line :  rhdm-7.5.0.DM-redhat-${buildDate}-kie-server-ee8.zip
                                reAddComment(kieserverFile, "target: \"kie_server_distribution.zip\"",
                                        String.format("  # %s", kieserverFileName));

                                // find target: "slf4j-simple.jar"
                                // and add comment on next line :  slf4j-simple-1.7.22.redhat-2.jar
                                reAddComment(kieserverFile, "target: \"slf4j-simple.jar\"", "  # slf4j-simple-1.7.22.redhat-2.jar");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    // Prepare optaweb changes
                    String employeeWarFileName = String.format("employee-rostering-distribution-%s.redhat-%s/binaries/employee-rostering-webapp-%s.redhat-%s.war",
                            cacherProperties.version(), buildDate, cacherProperties.version(), buildDate);
                    optaweb.getEnvs().stream().forEach(env -> {
                        if (env.getName().equals("EMPLOYEE_ROSTERING_DISTRIBUTION_WAR")) {

                            log.fine(String.format("Update employee-rostering-webapp file from [%s] to [%s]", env.getValue(), employeeWarFileName));
                            env.setValue(employeeWarFileName);
                            yamlFilesHelper.writeModule(optaweb, optawebFile);
                        }
                    });
                    optaweb.getArtifacts().stream().forEach(artifact -> {
                        if (artifact.getName().equals("ADD_ONS_DISTRIBUTION_ZIP")) {
                            String optawebFileName = String.format("rhdm-%s.DM-redhat-%s-add-ons.zip", cacherProperties.version(), buildDate);
                            String optawebCheckSum;
                            try {
                                optawebCheckSum = elements.get(optawebFileName).getChecksum();

                                log.fine(String.format("Updating RHDM Optaweb add-ons from [%s] to [%s]", artifact.getMd5(), optawebCheckSum));
                                artifact.setMd5(optawebCheckSum);
                                yamlFilesHelper.writeModule(optaweb, optawebFile);

                                // find target: "add_ons_distribution.zip"
                                // and add comment on next line :  rhdm-7.5.0.DM-redhat-${buildDate}-kie-server-ee8.zip
                                reAddComment(optawebFile, "target: \"add_ons_distribution.zip\"",
                                        String.format("  # %s", optawebFileName));
                                // find target: "employee-rostering-distribution-7.5.0.redhat-${buildDate}/binaries/employee-rostering-webapp-7.5.0.redhat-${buildDate}.war"
                                // and add comment on next line : # remember to also update "EMPLOYEE_ROSTERING_DISTRIBUTION_WAR" value
                                reAddComment(optawebFile, String.format("  value: \"%s\"",employeeWarFileName),
                                        "# remember to also update \"EMPLOYEE_ROSTERING_DISTRIBUTION_WAR\" value");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });


                    if (gitRepository.addChanges("rhdm-7-image") && gitRepository.commitChanges("rhdm-7-image", buildDate, "Applying RHDM nightly build for build date " + buildDate)) {
                        log.fine("About to send Pull Request on rhdm-7-image git repository on branch " + buildDate);

                        String prTittle = "Updating RHDM artifacts based on the latest nightly build  " + buildDate;
                        String prDescription = "This PR was created automatically, please review carefully before merge, the" +
                                " base build date is " + buildDate;
                        pullRequestSender.performPullRequest("rhdm-7-image", buildDate, prTittle, prDescription);

                        gitRepository.handleBranch(BranchOperation.DELETE_BRANCH, buildDate, "rhdm-7-image");

                    } else {
                        log.warning("something went wrong while preparing the rhdm-7-image for the pull request");
                    }

                    // remove RHDM files elements
                    removeItems("rhdm");

                }

            } else {
                log.info("File " + fileName + " not found on the elements map. ignoring...");
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Re-add comments on the module.yaml file.
     *
     * @param fileName
     * @param linePattern
     * @param comment
     */
    public void reAddComment(String fileName, String linePattern, String comment) {
        try (Stream<String> lines = Files.lines(Paths.get(fileName))) {
            List<String> replaced = lines.map(line -> line.replace(linePattern, linePattern + "\n" + comment))
                    .collect(Collectors.toList());
            Files.write(Paths.get(fileName), replaced);
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    public void removeItems(String pattern) {
        log.fine("Element items are: " + Arrays.asList(elements));
        elements.entrySet().removeIf(entry -> entry.getKey().contains(pattern));
    }

    /**
     * Expose the elements Map for test purpose
     */
    public Map<String, PlainArtifact> getElements() {
        return elements;
    }

    /**
     * Verify if the elements HashMap contains all required rhpam files
     *
     * @return true if the files are ready or false if its not ready
     */
    private boolean isRhpamReadyForPR() {
        boolean isReady = true;
        HashMap<String, PlainArtifact> rhpam = new HashMap<>();
        elements.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("rhpam-"))
                .forEach(entry -> rhpam.put(entry.getKey(), entry.getValue()));

        for (Map.Entry<String, PlainArtifact> element : rhpam.entrySet()) {
            if (element.getValue().getChecksum().isEmpty()) {
                isReady = false;
            }
        }
        return isReady && rhpam.size() == 4;
    }

    /**
     * Verify if the elements HashMap contains all required rhdm files
     *
     * @return true if the files are ready or false if its not ready
     */
    private boolean isRhdmReadyForPR() {
        boolean isReady = true;
        HashMap<String, PlainArtifact> rhdm = new HashMap<>();
        elements.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("rhdm-"))
                .forEach(entry -> rhdm.put(entry.getKey(), entry.getValue()));

        for (Map.Entry<String, PlainArtifact> element : rhdm.entrySet()) {
            if (element.getValue().getChecksum().isEmpty()) {
                isReady = false;
            }
        }
        return isReady && rhdm.size() == 3;
    }


}