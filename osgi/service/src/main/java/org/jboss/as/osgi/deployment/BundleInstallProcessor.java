/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.osgi.deployment;

import static org.jboss.as.osgi.service.InitialDeploymentTracker.INITIAL_DEPLOYMENTS_COMPLETE;
import static org.jboss.as.server.deployment.Attachments.BUNDLE_STATE_KEY;

import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.osgi.service.InitialDeploymentTracker;
import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.as.server.deployment.Attachments.BundleState;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.BundleManager;
import org.jboss.osgi.framework.spi.IntegrationService;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.framework.spi.StorageStatePlugin;
import org.osgi.framework.BundleException;

/**
 * Processes deployments that have OSGi metadata attached.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 20-Sep-2010
 */
public class BundleInstallProcessor implements DeploymentUnitProcessor {

    private final AttachmentKey<ServiceName> BUNDLE_INSTALL_SERVICE = AttachmentKey.create(ServiceName.class);
    private final InitialDeploymentTracker deploymentTracker;

    public BundleInstallProcessor(InitialDeploymentTracker deploymentTracker) {
        this.deploymentTracker = deploymentTracker;
    }

    @Override
    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit depUnit = phaseContext.getDeploymentUnit();
        final Deployment deployment = depUnit.getAttachment(OSGiConstants.DEPLOYMENT_KEY);
        if (deployment == null)
            return;

        ServiceName serviceName;
        try {
            final BundleManager bundleManager = depUnit.getAttachment(OSGiConstants.BUNDLE_MANAGER_KEY);
            if (deploymentTracker.hasDeploymentName(depUnit.getName())) {
                restoreStorageState(phaseContext, deployment);
            }
            serviceName = bundleManager.installBundle(deployment, null);
        } catch (BundleException ex) {
            throw new DeploymentUnitProcessingException(ex);
        }

        // Add a dependency on the next phase for this bundle to be installed
        phaseContext.addDeploymentDependency(serviceName, OSGiConstants.BUNDLE_KEY);

        // Add a dependency on the next phase for all persisten bundles to be installed
        if (deploymentTracker.isComplete() == false) {
            phaseContext.addDeploymentDependency(INITIAL_DEPLOYMENTS_COMPLETE, AttachmentKey.create(Object.class));
        }

        depUnit.putAttachment(BUNDLE_STATE_KEY, BundleState.INSTALLED);
        depUnit.putAttachment(BUNDLE_INSTALL_SERVICE, serviceName);
    }

    @Override
    public void undeploy(final DeploymentUnit depUnit) {
        ServiceName serviceName = depUnit.getAttachment(BUNDLE_INSTALL_SERVICE);
        ServiceController<?> controller = serviceName != null ? depUnit.getServiceRegistry().getService(serviceName) : null;
        if (controller != null) {
            controller.setMode(Mode.REMOVE);
            depUnit.putAttachment(BUNDLE_STATE_KEY, BundleState.UNINSTALLED);
        }
    }

    private void restoreStorageState(final DeploymentPhaseContext phaseContext, final Deployment deployment) {
        ServiceRegistry serviceRegistry = phaseContext.getServiceRegistry();
        StorageStatePlugin storageProvider = (StorageStatePlugin) serviceRegistry.getRequiredService(IntegrationService.STORAGE_STATE_PLUGIN).getValue();
        StorageState storageState = storageProvider.getStorageState(deployment.getLocation());
        if (storageState != null) {
            deployment.setAutoStart(storageState.isPersistentlyStarted());
            deployment.addAttachment(StorageState.class, storageState);
        }
    }
}
