/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.v3.admin.cluster;

import java.util.Iterator;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.File;
import java.io.IOException;

import com.sun.enterprise.admin.remote.RemoteAdminCommand;
import com.sun.enterprise.admin.util.RemoteInstanceCommandHelper;
import com.sun.enterprise.config.serverbeans.*;
import com.sun.enterprise.module.ModulesRegistry;
import com.sun.enterprise.module.Module;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.v3.admin.StopServer;
import org.glassfish.api.ActionReport;
import org.glassfish.api.Async;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandLock;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.server.ServerEnvironmentImpl;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.cluster.ssh.connect.RemoteConnectHelper;
import com.sun.enterprise.util.SystemPropertyConstants;

import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PostConstruct;
import org.jvnet.hk2.component.PerLookup;
import org.glassfish.cluster.ssh.launcher.SSHLauncher;
import org.glassfish.cluster.ssh.sftp.SFTPClient;

/**
 * AdminCommand to stop the instance
 * server.
 * Shutdown of an instance.
 * This command only runs on DAS.  It calls the instance and asks it to
 * kill itself

 * @author Byron Nevins
 */
@Service(name = "stop-instance")
@Scoped(PerLookup.class)
@CommandLock(CommandLock.LockType.NONE) // allow stop-instance always
@I18n("stop.instance.command")
@ExecuteOn(RuntimeType.DAS)
public class StopInstanceCommand extends StopServer implements AdminCommand, PostConstruct {

    @Inject
    private Habitat habitat;
    @Inject
    private ServerContext serverContext;    
    @Inject
    private Nodes nodes;
    @Inject
    private ServerEnvironment env;
    @Inject
    Node[] nodeList;
    @Inject
    private ModulesRegistry registry;
    @Param(optional = true, defaultValue = "true")
    private Boolean force;
    @Param(optional = false, primary = true)
    private String instanceName;
    private Logger logger;
    private RemoteInstanceCommandHelper helper;
    private ActionReport report;
    private String errorMessage = null;
    private Server instance;
    File pidFile = null;
    SFTPClient ftpClient=null;    


    public void execute(AdminCommandContext context) {
        report = context.getActionReport();
        logger = context.getLogger();
        SSHLauncher launcher;
        RemoteConnectHelper rch;
        int dasPort;
        String dasHost;

        if (env.isDas())
            errorMessage = callInstance();
        else
            errorMessage = Strings.get("stop.instance.notDas",
                    env.getRuntimeType().toString());
        
        if(errorMessage == null) {
            errorMessage = pollForDeath();
        }

        if (errorMessage != null) {
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(errorMessage);
            return;
        }

        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
        report.setMessage(Strings.get("stop.instance.success",
                    instanceName));
        // we think the instance is down but it might not be completely down so do further checking
        // get the node name and then the node
        // if localhost check if files exists
        // else if SSH check if file exists  on remote system
        // else can't check anything else.
        String nodeName = instance.getNode();
        Node node = nodes.getNode(nodeName);
        String nodeHost = node.getNodeHost();
        InstanceDirUtils insDU = new InstanceDirUtils(node, serverContext);
        try {
            pidFile = new File (insDU.getLocalInstanceDir(instance.getName()) , "config/pid");
        } catch (java.io.IOException eio){
            // could not get the file name so can't see if it still exists.  Need to exit
            return;
        }
        // this should be replaced with method from Node config bean.  
        dasPort = helper.getAdminPort(SystemPropertyConstants.DAS_SERVER_NAME);
        dasHost = System.getProperty(SystemPropertyConstants.HOST_NAME_PROPERTY);
        rch = new RemoteConnectHelper(habitat, node, logger, dasHost, dasPort);

        if (rch.isLocalhost()){
            if (pidFile.exists()){
                    //server still not down completely, do we poll?
                errorMessage = pollForRealDeath("local");
            }

        } else if (node.getType().equals("SSH")) {
            //use SFTPClient to see if file exists.
            launcher = habitat.getComponent(SSHLauncher.class);
            launcher.init(node, logger);
            try {
                ftpClient = launcher.getSFTPClient();
                if (ftpClient.exists(pidFile.toString())){
                    // server still not down, do we poll?
                    errorMessage = pollForRealDeath("remote");
                }
            } catch (IOException ex) {
                //could not get to other host
            }
        }

        if (errorMessage != null) {
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(errorMessage);
        }
        
    }

    @Override
    public void postConstruct() {
        helper = new RemoteInstanceCommandHelper(habitat);
    }

    /**
     * return null if all went OK...
     *
     */
    private String callInstance() {
        String cmdName = "stop-instance";

        if (!StringUtils.ok(instanceName))
            return Strings.get("stop.instance.noInstanceName", cmdName);

        instance = helper.getServer(instanceName);

        if (instance == null)
            return Strings.get("stop.instance.noSuchInstance", instanceName);

        String host = instance.getAdminHost();

        if (host == null)
            return Strings.get("stop.instance.noHost", instanceName);

        int port = helper.getAdminPort(instance);

        if (port < 0)
            return Strings.get("stop.instance.noPort", instanceName);

        if(!instance.isRunning())
            return null;

        try {
            // TODO username password ????
            logger.info(Strings.get("stop.instance.init", instanceName));
            RemoteAdminCommand rac = new RemoteAdminCommand("_stop-instance",
                    host, port, false, "admin", null, logger);

            // notice how we do NOT send in the instance's name as an operand!!
            rac.executeCommand(new ParameterMap());
        }
        catch (CommandException ex) {
            return Strings.get("stop.instance.racError", instanceName,
                    ex.getLocalizedMessage());
        }

        return null;
    }

    // return null means A-OK
    private String pollForDeath() {
        int counter = 0;  // 120 seconds

        while (++counter < 240) {
            if (!instance.isRunning())
                return null;

            try {
                Thread.sleep(500);
            }
            catch (Exception e) {
                // ignore
            }
        }
        return Strings.get("stop.instance.timeout", instanceName);
    }
    
    private String pollForRealDeath(String mode){
        int counter = 0;  // 30 seconds

        // 24 * 5 = 120 seconds
        while (++counter < 24) {
            try {
                if (mode.equals("local")){
                    if(!pidFile.exists()){
                        return null;
                    }
                }else {
                    if (!ftpClient.exists(pidFile.toString()))
                        return null;
                }
                // Fairly long interval between tries because checking over
                // SSH is expensive.
                Thread.sleep(5000);
            } catch (Exception e) {
                // ignore
            }

        }
        return Strings.get("stop.instance.timeout.completely", instanceName);

    }
}
