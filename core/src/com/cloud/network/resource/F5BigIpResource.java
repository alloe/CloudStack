/**
 * *  Copyright (C) 2011 Citrix Systems, Inc.  All rights reserved
*
 *
 * This software is licensed under the GNU General Public License v3 or later.
 *
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.cloud.network.resource;

import iControl.CommonEnabledState;
import iControl.CommonIPPortDefinition;
import iControl.CommonStatistic;
import iControl.CommonStatisticType;
import iControl.CommonVirtualServerDefinition;
import iControl.Interfaces;
import iControl.LocalLBLBMethod;
import iControl.LocalLBNodeAddressBindingStub;
import iControl.LocalLBPoolBindingStub;
import iControl.LocalLBProfileContextType;
import iControl.LocalLBVirtualServerBindingStub;
import iControl.LocalLBVirtualServerVirtualServerPersistence;
import iControl.LocalLBVirtualServerVirtualServerProfile;
import iControl.LocalLBVirtualServerVirtualServerResource;
import iControl.LocalLBVirtualServerVirtualServerStatisticEntry;
import iControl.LocalLBVirtualServerVirtualServerStatistics;
import iControl.LocalLBVirtualServerVirtualServerType;
import iControl.NetworkingMemberTagType;
import iControl.NetworkingMemberType;
import iControl.NetworkingRouteDomainBindingStub;
import iControl.NetworkingSelfIPBindingStub;
import iControl.NetworkingVLANBindingStub;
import iControl.NetworkingVLANMemberEntry;
import iControl.SystemConfigSyncBindingStub;
import iControl.SystemConfigSyncSaveMode;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.ExternalNetworkResourceUsageAnswer;
import com.cloud.agent.api.ExternalNetworkResourceUsageCommand;
import com.cloud.agent.api.MaintainAnswer;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupExternalLoadBalancerCommand;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.IpAssocAnswer;
import com.cloud.agent.api.routing.LoadBalancerConfigCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.LoadBalancerTO;
import com.cloud.agent.api.to.LoadBalancerTO.DestinationTO;
import com.cloud.host.Host;
import com.cloud.resource.ServerResource;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.exception.ExecutionException;
import com.cloud.utils.net.NetUtils;

public class F5BigIpResource implements ServerResource {
	
	private enum LbAlgorithm {
		RoundRobin(null, LocalLBLBMethod.LB_METHOD_ROUND_ROBIN),
		LeastConn(null, LocalLBLBMethod.LB_METHOD_LEAST_CONNECTION_MEMBER);
		
		String persistenceProfileName;
		LocalLBLBMethod method;
		
		LbAlgorithm(String persistenceProfileName, LocalLBLBMethod method) {
			this.persistenceProfileName = persistenceProfileName;
			this.method = method;
		}
		
		public String getPersistenceProfileName() {
			return persistenceProfileName;
		}
		
		public LocalLBLBMethod getMethod() {
			return method;
		}		
	}
	
	private enum LbProtocol {
		tcp,
		udp;
	}

	private String _name;
	private String _zoneId;
	private String _ip;
	private String _username;
	private String _password;
	private String _publicInterface;
	private String _privateInterface;
	private Integer _numRetries; 
	private String _guid;
	private boolean _inline;

	private Interfaces _interfaces;
	private LocalLBVirtualServerBindingStub _virtualServerApi;
	private LocalLBPoolBindingStub _loadbalancerApi;
	private LocalLBNodeAddressBindingStub _nodeApi;
	private NetworkingVLANBindingStub _vlanApi;
	private NetworkingSelfIPBindingStub _selfIpApi;
	private NetworkingRouteDomainBindingStub _routeDomainApi;
	private SystemConfigSyncBindingStub _configSyncApi;
	private String _objectNamePathSep = "-";
	private String _routeDomainIdentifier = "%";
	
	private static final Logger s_logger = Logger.getLogger(F5BigIpResource.class);
	
	@Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
    	try {    		    		
    		XTrustProvider.install();
    		
    		_name = (String) params.get("name");
    		if (_name == null) {
    			throw new ConfigurationException("Unable to find name");
    		}
    		
    		_zoneId = (String) params.get("zoneId");
    		if (_zoneId == null) {
    			throw new ConfigurationException("Unable to find zone");
    		}
    		
    		_ip = (String) params.get("ip");
    		if (_ip == null) {
    			throw new ConfigurationException("Unable to find IP");
    		}
    		
    		_username = (String) params.get("username");
    		if (_username == null) {
    			throw new ConfigurationException("Unable to find username");
    		}
    		
    		_password = (String) params.get("password");
    		if (_password == null) {
    			throw new ConfigurationException("Unable to find password");
    		}    		    		
    		
    		_publicInterface = (String) params.get("publicInterface");
    		if (_publicInterface == null) {
    			throw new ConfigurationException("Unable to find public interface");
    		}
    		
    		_privateInterface = (String) params.get("privateInterface");
    		if (_privateInterface == null) {
    			throw new ConfigurationException("Unable to find private interface");
    		}
    		
    		_numRetries = NumbersUtil.parseInt((String) params.get("numRetries"), 1);
			    		
    		_guid = (String)params.get("guid");
            if (_guid == null) {
                throw new ConfigurationException("Unable to find the guid");
            }
            
            _inline = Boolean.parseBoolean((String) params.get("inline"));
    		    		    	
            if (!login()) {
            	throw new ExecutionException("Failed to login to the F5 BigIp.");
            }
    		    		
    		return true;
    	} catch (Exception e) {
    		throw new ConfigurationException(e.getMessage());
    	}
    	
    }

	@Override
    public StartupCommand[] initialize() {   
		StartupExternalLoadBalancerCommand cmd = new StartupExternalLoadBalancerCommand();
		cmd.setName(_name);
		cmd.setDataCenter(_zoneId);
		cmd.setPod("");
    	cmd.setPrivateIpAddress(_ip);
    	cmd.setStorageIpAddress("");
    	cmd.setVersion("");
    	cmd.setGuid(_guid);
    	return new StartupCommand[]{cmd};
    }

	@Override
    public Host.Type getType() {
		return Host.Type.ExternalLoadBalancer;
	}
	
	@Override
	public String getName() {
		return _name;
	}
	
	@Override
    public PingCommand getCurrentStatus(final long id) {
		return new PingCommand(Host.Type.ExternalLoadBalancer, id);
    }
	
	@Override
	public boolean start() {
		return true;
	}

	@Override
	public boolean stop() {
		return true;
	}

	@Override
	public void disconnected() {
		return;
	}		

	@Override
    public IAgentControl getAgentControl() {
		return null;
	}

	@Override
    public void setAgentControl(IAgentControl agentControl) {
		return;
	}

	@Override
    public Answer executeRequest(Command cmd) {
		return executeRequest(cmd, _numRetries);
	}		
	
	private Answer executeRequest(Command cmd, int numRetries) {
		if (cmd instanceof ReadyCommand) {
			return execute((ReadyCommand) cmd);
		} else if (cmd instanceof MaintainCommand) {
			return execute((MaintainCommand) cmd);
		} else if (cmd instanceof IpAssocCommand) {
			return execute((IpAssocCommand) cmd, numRetries);
		} else if (cmd instanceof LoadBalancerConfigCommand) {
			return execute((LoadBalancerConfigCommand) cmd, numRetries);
		} else if (cmd instanceof ExternalNetworkResourceUsageCommand) {
			return execute((ExternalNetworkResourceUsageCommand) cmd);
		} else {
			return Answer.createUnsupportedCommandAnswer(cmd);
		}
	}
	
	private Answer retry(Command cmd, int numRetries) {				
		int numRetriesRemaining = numRetries - 1;
		s_logger.error("Retrying " + cmd.getClass().getSimpleName() + ". Number of retries remaining: " + numRetriesRemaining);
		return executeRequest(cmd, numRetriesRemaining);	
	}
	
	private boolean shouldRetry(int numRetries) {
		return (numRetries > 0 && login());
	}
	
	private Answer execute(ReadyCommand cmd) {
        return new ReadyAnswer(cmd);
    }
	
	private Answer execute(MaintainCommand cmd) {
		return new MaintainAnswer(cmd);
	}	
	
	private synchronized Answer execute(IpAssocCommand cmd, int numRetries) {
	    String[] results = new String[cmd.getIpAddresses().length];
        int i = 0;
		try {		
			IpAddressTO[] ips = cmd.getIpAddresses();
            for (IpAddressTO ip : ips) {
                long guestVlanTag = Long.valueOf(ip.getVlanId());
                String vlanSelfIp = _inline ? tagAddressWithRouteDomain(ip.getVlanGateway(), guestVlanTag) : ip.getVlanGateway();
                String vlanNetmask = ip.getVlanNetmask();      
                
                // Delete any existing guest VLAN with this tag, self IP, and netmask
                deleteGuestVlan(guestVlanTag, vlanSelfIp, vlanNetmask);
                
                if (ip.isAdd()) {
                	// Add a new guest VLAN
                    addGuestVlan(guestVlanTag, vlanSelfIp, vlanNetmask);
                }
                
                saveConfiguration();               
                results[i++] = ip.getPublicIp() + " - success";
            }
                        
		} catch (ExecutionException e) {
			s_logger.error("Failed to execute IPAssocCommand due to " + e);				    
		    
		    if (shouldRetry(numRetries)) {
		    	return retry(cmd, numRetries);
		    } else {
		    	results[i++] = IpAssocAnswer.errorResult;
		    }
		}		
		
		return new IpAssocAnswer(cmd, results);
	}
	
	private synchronized Answer execute(LoadBalancerConfigCommand cmd, int numRetries) {
		try {			
			long guestVlanTag = Long.parseLong(cmd.getAccessDetail(NetworkElementCommand.GUEST_VLAN_TAG));
			LoadBalancerTO[] loadBalancers = cmd.getLoadBalancers();
			for (LoadBalancerTO loadBalancer : loadBalancers) {
				LbProtocol lbProtocol;
				try {
					if (loadBalancer.getProtocol() == null) {
						lbProtocol = LbProtocol.tcp;
					} else {
						lbProtocol = LbProtocol.valueOf(loadBalancer.getProtocol());
					}
				} catch (IllegalArgumentException e) {
					throw new ExecutionException("Got invalid protocol: " + loadBalancer.getProtocol());
				}
				
				LbAlgorithm lbAlgorithm;
				if (loadBalancer.getAlgorithm().equals("roundrobin")) {
					lbAlgorithm = LbAlgorithm.RoundRobin;
				} else if (loadBalancer.getAlgorithm().equals("leastconn")) {
					lbAlgorithm = LbAlgorithm.LeastConn;
				} else {
					throw new ExecutionException("Got invalid algorithm: " + loadBalancer.getAlgorithm());
				}		
				
				String srcIp = _inline ? tagAddressWithRouteDomain(loadBalancer.getSrcIp(), guestVlanTag) : loadBalancer.getSrcIp();
				int srcPort = loadBalancer.getSrcPort();	
				String virtualServerName = genVirtualServerName(lbProtocol, srcIp, srcPort);
												
				boolean destinationsToAdd = false;
				for (DestinationTO destination : loadBalancer.getDestinations()) {
					if (!destination.isRevoked()) {
						destinationsToAdd = true;
						break;
					}
				}
				
				if (!loadBalancer.isRevoked() && destinationsToAdd) {		
					// Add the pool 
					addPool(virtualServerName, lbAlgorithm);
					
					// Add pool members  
					List<String> activePoolMembers = new ArrayList<String>();
					for (DestinationTO destination : loadBalancer.getDestinations()) {
						if (!destination.isRevoked()) {
							String destIp = _inline ? tagAddressWithRouteDomain(destination.getDestIp(), guestVlanTag) : destination.getDestIp();
							addPoolMember(virtualServerName, destIp, destination.getDestPort());
							activePoolMembers.add(destIp + "-" + destination.getDestPort());
						}
					}			
					
					// Delete any pool members that aren't in the current list of destinations
					deleteInactivePoolMembers(virtualServerName, activePoolMembers);
					
					// Add the virtual server 
					addVirtualServer(virtualServerName, lbProtocol, srcIp, srcPort);										
				} else {
					// Delete the virtual server with this protocol, source IP, and source port, along with its default pool and all pool members
					deleteVirtualServerAndDefaultPool(virtualServerName);			
				}
			}																																																		
			
			saveConfiguration();				
			return new Answer(cmd);		
		} catch (ExecutionException e) {
			s_logger.error("Failed to execute LoadBalancerConfigCommand due to " + e);
			
			if (shouldRetry(numRetries)) {
				return retry(cmd, numRetries);
			} else {
				return new Answer(cmd, e);
			}
			
		}
	}		
	
	private synchronized ExternalNetworkResourceUsageAnswer execute(ExternalNetworkResourceUsageCommand cmd) {
		try {
			return getIpBytesSentAndReceived(cmd);
		} catch (ExecutionException e) {
			return new ExternalNetworkResourceUsageAnswer(cmd, e);
		}
	}
	
	private void saveConfiguration() throws ExecutionException {
		try {
			_configSyncApi.save_configuration("", SystemConfigSyncSaveMode.SAVE_BASE_LEVEL_CONFIG);		
			_configSyncApi.save_configuration("", SystemConfigSyncSaveMode.SAVE_HIGH_LEVEL_CONFIG);		
			s_logger.debug("Successfully saved F5 BigIp configuration.");
		} catch (RemoteException e) {
			s_logger.error("Failed to save F5 BigIp configuration due to: " + e);
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private void addGuestVlan(long vlanTag, String vlanSelfIp, String vlanNetmask) throws ExecutionException {
		try {
			String vlanName = genVlanName(vlanTag);	
			List<String> allVlans = getVlans();
			if (!allVlans.contains(vlanName)) {				
				String[] vlanNames = genStringArray(vlanName);
				long[] vlanTags = genLongArray(vlanTag);
				CommonEnabledState[] commonEnabledState = {CommonEnabledState.STATE_DISABLED};
				
				// Create the interface name
				NetworkingVLANMemberEntry[][] vlanMemberEntries = {{new NetworkingVLANMemberEntry()}};
				vlanMemberEntries[0][0].setMember_type(NetworkingMemberType.MEMBER_INTERFACE);
				vlanMemberEntries[0][0].setTag_state(NetworkingMemberTagType.MEMBER_TAGGED);
				vlanMemberEntries[0][0].setMember_name(_privateInterface);
					
				s_logger.debug("Creating a guest VLAN with tag " + vlanTag);
				_vlanApi.create(vlanNames, vlanTags, vlanMemberEntries, commonEnabledState, new long[]{10L}, new String[]{"00:00:00:00:00:00"});		
				
				if (!getVlans().contains(vlanName)) {
					throw new ExecutionException("Failed to create vlan with tag " + vlanTag);
				}
			}
			
			if (_inline) {
				List<Long> allRouteDomains = getRouteDomains();
				if (!allRouteDomains.contains(vlanTag)) {
					long[] routeDomainIds = genLongArray(vlanTag);
					String[][] vlanNames = new String[][]{genStringArray(genVlanName(vlanTag))};
					
					s_logger.debug("Creating route domain " + vlanTag);
					_routeDomainApi.create(routeDomainIds, vlanNames);
					
					if (!getRouteDomains().contains(vlanTag)) {
						throw new ExecutionException("Failed to create route domain " + vlanTag);
					}
				}
			}
			
			List<String> allSelfIps = getSelfIps();
			if (!allSelfIps.contains(vlanSelfIp)) {
				String[] selfIpsToCreate = genStringArray(vlanSelfIp);
				String[] vlans = genStringArray(vlanName);
				String[] netmasks = genStringArray(vlanNetmask);
				long[] unitIds = genLongArray(0L);
				CommonEnabledState[] enabledStates = new CommonEnabledState[]{CommonEnabledState.STATE_DISABLED};
				
				s_logger.debug("Creating self IP " + vlanSelfIp);
				_selfIpApi.create(selfIpsToCreate, vlans, netmasks, unitIds, enabledStates);
				
				if (!getSelfIps().contains(vlanSelfIp)) {
					throw new ExecutionException("Failed to create self IP " + vlanSelfIp);
				}
			}
		} catch (RemoteException e) {
			s_logger.error(e);
			throw new ExecutionException(e.getMessage());
		}
			
	}
	
	private void deleteGuestVlan(long vlanTag, String vlanSelfIp, String vlanNetmask) throws ExecutionException {
		try {
			// Delete all virtual servers and pools that use this guest VLAN
			deleteVirtualServersInGuestVlan(vlanSelfIp, vlanNetmask);

			List<String> allSelfIps = getSelfIps();
			if (allSelfIps.contains(vlanSelfIp)) {
				s_logger.debug("Deleting self IP " + vlanSelfIp);
				_selfIpApi.delete_self_ip(genStringArray(vlanSelfIp));

				if (getSelfIps().contains(vlanSelfIp)) {
					throw new ExecutionException("Failed to delete self IP " + vlanSelfIp);
				}
			}
			
			if (_inline) {
				List<Long> allRouteDomains = getRouteDomains();
				if (allRouteDomains.contains(vlanTag)) {
					s_logger.debug("Deleting route domain " + vlanTag);
					_routeDomainApi.delete_route_domain(genLongArray(vlanTag));
					
					if (getRouteDomains().contains(vlanTag)) {
						throw new ExecutionException("Failed to delete route domain " + vlanTag);
					}
				}
			}

			String vlanName = genVlanName(vlanTag);	
			List<String> allVlans = getVlans();
			if (allVlans.contains(vlanName)) {
				_vlanApi.delete_vlan(genStringArray(vlanName));

				if (getVlans().contains(vlanName)) {
					throw new ExecutionException("Failed to delete VLAN with tag: " + vlanTag);
				}
			}				
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private void deleteVirtualServersInGuestVlan(String vlanSelfIp, String vlanNetmask) throws ExecutionException {
		vlanSelfIp = stripRouteDomainFromAddress(vlanSelfIp);
		List<String> virtualServersToDelete = new ArrayList<String>();
		
		List<String> allVirtualServers = getVirtualServers();
		for (String virtualServerName : allVirtualServers) {
			// Check if the virtual server's default pool has members in this guest VLAN
			List<String> poolMembers = getMembers(virtualServerName);
			for (String poolMemberName : poolMembers) {
				String poolMemberIp = stripRouteDomainFromAddress(getIpAndPort(poolMemberName)[0]);
				if (NetUtils.sameSubnet(vlanSelfIp, poolMemberIp, vlanNetmask)) {
					virtualServersToDelete.add(virtualServerName);
					break;
				}
			}			
		}
		
		for (String virtualServerName : virtualServersToDelete) {
			s_logger.debug("Found a virtual server (" + virtualServerName + ") for guest network with self IP " + vlanSelfIp + " that is active when the guest network is being destroyed.");
			deleteVirtualServerAndDefaultPool(virtualServerName);
		}
	}
	
	private String genVlanName(long vlanTag) {
		return "vlan-" + String.valueOf(vlanTag);
	}
	
	private List<Long> getRouteDomains() throws ExecutionException {
		try {
			List<Long> routeDomains = new ArrayList<Long>();
			long[] routeDomainsArray = _routeDomainApi.get_list();
			
			for (long routeDomainName : routeDomainsArray) {
				routeDomains.add(routeDomainName);
			}
			
			return routeDomains;
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private List<String> getSelfIps() throws ExecutionException {
		try {
			List<String> selfIps = new ArrayList<String>();
			String[] selfIpsArray = _selfIpApi.get_list();

			for (String selfIp : selfIpsArray) {
				selfIps.add(selfIp);
			}

			return selfIps;
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private List<String> getVlans() throws ExecutionException {
		try {
			List<String> vlans = new ArrayList<String>();
			String[] vlansArray = _vlanApi.get_list();

			for (String vlan : vlansArray) {
				vlans.add(vlan);
			}

			return vlans;
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	// Login	
	
	private boolean login() {
		try {			
			_interfaces = new Interfaces();
			
			if (!_interfaces.initialize(_ip, _username, _password)) {
				throw new ExecutionException("Failed to log in to BigIp appliance");
			}
			
			_virtualServerApi = _interfaces.getLocalLBVirtualServer();
			_loadbalancerApi = _interfaces.getLocalLBPool();		
			_nodeApi = _interfaces.getLocalLBNodeAddress();
			_vlanApi = _interfaces.getNetworkingVLAN();
			_selfIpApi = _interfaces.getNetworkingSelfIP();
			_routeDomainApi = _interfaces.getNetworkingRouteDomain();
			_configSyncApi = _interfaces.getSystemConfigSync();
			
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	// Virtual server methods
	
	private void addVirtualServer(String virtualServerName, LbProtocol protocol, String srcIp, int srcPort) throws ExecutionException {
		try {
			if (!virtualServerExists(virtualServerName)) {
				s_logger.debug("Adding virtual server " + virtualServerName);
				_virtualServerApi.create(genVirtualServerDefinition(virtualServerName, protocol, srcIp, srcPort), new String[]{"255.255.255.255"}, genVirtualServerResource(virtualServerName), genVirtualServerProfile(protocol));
				_virtualServerApi.set_snat_automap(genStringArray(virtualServerName));

				if (!virtualServerExists(virtualServerName)) {
					throw new ExecutionException("Failed to add virtual server " + virtualServerName);
				}
			}
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private void deleteVirtualServerAndDefaultPool(String virtualServerName) throws ExecutionException {
		try {
			if (virtualServerExists(virtualServerName)) {
				// Delete the default pool's members
				List<String> poolMembers = getMembers(virtualServerName);
				for (String poolMember : poolMembers) {
					String[] destIpAndPort = getIpAndPort(poolMember);
					deletePoolMember(virtualServerName, destIpAndPort[0], Integer.valueOf(destIpAndPort[1]));
				}

				// Delete the virtual server
				s_logger.debug("Deleting virtual server " + virtualServerName);
				_virtualServerApi.delete_virtual_server(genStringArray(virtualServerName));

				if (getVirtualServers().contains(virtualServerName)) {
					throw new ExecutionException("Failed to delete virtual server " + virtualServerName);
				}	

				// Delete the default pool
				deletePool(virtualServerName);	
			}
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private String genVirtualServerName(LbProtocol protocol, String srcIp, long srcPort) {
		srcIp = stripRouteDomainFromAddress(srcIp);
		return genObjectName("vs", protocol, srcIp, srcPort);
	}
	
	private boolean virtualServerExists(String virtualServerName) throws ExecutionException {
		return getVirtualServers().contains(virtualServerName);
	}
	
	private List<String> getVirtualServers() throws ExecutionException {
		try {
			List<String> virtualServers = new ArrayList<String>();
			String[] virtualServersArray = _virtualServerApi.get_list();

			for (String virtualServer : virtualServersArray) {
				virtualServers.add(virtualServer);
			}

			return virtualServers;
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private iControl.CommonVirtualServerDefinition[] genVirtualServerDefinition(String name, LbProtocol protocol, String srcIp, long srcPort) {
		CommonVirtualServerDefinition vsDefs[] = {new CommonVirtualServerDefinition()};
		vsDefs[0].setName(name);
        vsDefs[0].setAddress(srcIp);
        vsDefs[0].setPort(srcPort);
        
        if (protocol.equals(LbProtocol.tcp)) {
        	vsDefs[0].setProtocol(iControl.CommonProtocolType.PROTOCOL_TCP);
        } else if (protocol.equals(LbProtocol.udp)) {
        	vsDefs[0].setProtocol(iControl.CommonProtocolType.PROTOCOL_UDP);
        }
        
		return vsDefs;
	}
	
	private iControl.LocalLBVirtualServerVirtualServerResource[] genVirtualServerResource(String poolName) {
		LocalLBVirtualServerVirtualServerResource vsRes[] = {new LocalLBVirtualServerVirtualServerResource()};
		vsRes[0].setType(LocalLBVirtualServerVirtualServerType.RESOURCE_TYPE_POOL);
        vsRes[0].setDefault_pool_name(poolName);
		return vsRes;
	}
	
	private LocalLBVirtualServerVirtualServerProfile[][] genVirtualServerProfile(LbProtocol protocol) {
		LocalLBVirtualServerVirtualServerProfile vsProfs[][] = {{new LocalLBVirtualServerVirtualServerProfile()}};	
		vsProfs[0][0].setProfile_context(LocalLBProfileContextType.PROFILE_CONTEXT_TYPE_ALL);		
		
		if (protocol.equals(LbProtocol.tcp)) {					
			vsProfs[0][0].setProfile_name("http");
		} else if (protocol.equals(LbProtocol.udp)) {
			vsProfs[0][0].setProfile_name("udp");
		}
		
		return vsProfs;
	}
	
	private LocalLBVirtualServerVirtualServerPersistence[][] genPersistenceProfile(String persistenceProfileName) {
		LocalLBVirtualServerVirtualServerPersistence[][] persistenceProfs = {{new LocalLBVirtualServerVirtualServerPersistence()}};
		persistenceProfs[0][0].setDefault_profile(true);
		persistenceProfs[0][0].setProfile_name(persistenceProfileName);
		return persistenceProfs;
	}			
	
	// Load balancing pool methods
	
	private void addPool(String virtualServerName, LbAlgorithm algorithm) throws ExecutionException {
		try {
			if (!poolExists(virtualServerName)) {
				if (algorithm.getPersistenceProfileName() != null) {
					algorithm = LbAlgorithm.RoundRobin;
				}

				s_logger.debug("Adding pool for virtual server " + virtualServerName + " with algorithm " + algorithm);
				_loadbalancerApi.create(genStringArray(virtualServerName), genLbMethod(algorithm), genEmptyMembersArray());

				if (!poolExists(virtualServerName)) {
					throw new ExecutionException("Failed to create new pool for virtual server " + virtualServerName);
				}							
			}
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private void deletePool(String virtualServerName) throws ExecutionException {
		try {
			if (poolExists(virtualServerName) && getMembers(virtualServerName).size() == 0) {
				s_logger.debug("Deleting pool for virtual server " + virtualServerName);
				_loadbalancerApi.delete_pool(genStringArray(virtualServerName));
				
				if (poolExists(virtualServerName)) {
					throw new ExecutionException("Failed to delete pool for virtual server " + virtualServerName);
				}
			}
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}	
	
	private void addPoolMember(String virtualServerName, String destIp, int destPort) throws ExecutionException {
		try {
			String memberIdentifier = destIp + "-" + destPort;			

			if (poolExists(virtualServerName) && !memberExists(virtualServerName, memberIdentifier)) {
				s_logger.debug("Adding member " + memberIdentifier + " into pool for virtual server " + virtualServerName);
				_loadbalancerApi.add_member(genStringArray(virtualServerName), genMembers(destIp, destPort));

				if (!memberExists(virtualServerName, memberIdentifier)) {
					throw new ExecutionException("Failed to add new member " + memberIdentifier + " into pool for virtual server " + virtualServerName);
				}
			}
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private void deleteInactivePoolMembers(String virtualServerName, List<String> activePoolMembers) throws ExecutionException {
		List<String> allPoolMembers = getMembers(virtualServerName);
		
		for (String member : allPoolMembers) {
			if (!activePoolMembers.contains(member)) {
				String[] ipAndPort = member.split("-");
				deletePoolMember(virtualServerName, ipAndPort[0], Integer.valueOf(ipAndPort[1]));
			}
		}
	}
	
	private void deletePoolMember(String virtualServerName, String destIp, int destPort) throws ExecutionException {
		try {
			String memberIdentifier = destIp + "-" + destPort;			
			List<String> lbPools = getAllLbPools();

			if (lbPools.contains(virtualServerName) && memberExists(virtualServerName, memberIdentifier)) {
				s_logger.debug("Deleting member "  + memberIdentifier + " from pool for virtual server " + virtualServerName);
				_loadbalancerApi.remove_member(genStringArray(virtualServerName), genMembers(destIp, destPort));

				if (memberExists(virtualServerName, memberIdentifier)) {
					throw new ExecutionException("Failed to delete member " + memberIdentifier + " from pool for virtual server " + virtualServerName);
				}

				if (nodeExists(destIp)) {
					boolean nodeNeeded = false;
					done:
						for (String poolToCheck : lbPools) {
							for (String memberInPool : getMembers(poolToCheck)) {
								if (getIpAndPort(memberInPool)[0].equals(destIp)) {
									nodeNeeded = true;
									break done;
								}
							}		
						}						

					if (!nodeNeeded) {
						s_logger.debug("Deleting node " + destIp);
						_nodeApi.delete_node_address(genStringArray(destIp));

						if (nodeExists(destIp)) {
							throw new ExecutionException("Failed to delete node " + destIp);
						}
					}
				}
			}			
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private boolean poolExists(String poolName) throws ExecutionException {
		return getAllLbPools().contains(poolName);
	}
	
	private boolean memberExists(String poolName, String memberIdentifier) throws ExecutionException {
		return getMembers(poolName).contains(memberIdentifier);
	}
	
	private boolean nodeExists(String destIp) throws RemoteException {
		return getNodes().contains(destIp);
	}
	
	private String[] getIpAndPort(String memberIdentifier) {
		return memberIdentifier.split("-");
	}
	
	public List<String> getAllLbPools() throws ExecutionException {
		try {
			List<String> lbPools = new ArrayList<String>();
			String[] pools = _loadbalancerApi.get_list();

			for (String pool : pools) {
				lbPools.add(pool);
			}

			return lbPools;
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private List<String> getMembers(String virtualServerName) throws ExecutionException {
		try {
			List<String> members = new ArrayList<String>();
			String[] virtualServerNames = genStringArray(virtualServerName);
			CommonIPPortDefinition[] membersArray = _loadbalancerApi.get_member(virtualServerNames)[0];

			for (CommonIPPortDefinition member : membersArray) {
				members.add(member.getAddress() + "-" + member.getPort());
			}

			return members;
		} catch (RemoteException e) {
			throw new ExecutionException(e.getMessage());
		}
	}
	
	private List<String> getNodes() throws RemoteException {
		List<String> nodes = new ArrayList<String>();
		String[] nodesArray = _nodeApi.get_list();
		
		for (String node : nodesArray) {
			nodes.add(node);		
		}
		
		return nodes;
	}
	
	private iControl.CommonIPPortDefinition[][] genMembers(String destIp, long destPort) {
		iControl.CommonIPPortDefinition[] membersInnerArray = new iControl.CommonIPPortDefinition[1];
		membersInnerArray[0] = new iControl.CommonIPPortDefinition(destIp, destPort);
		return new iControl.CommonIPPortDefinition[][]{membersInnerArray};
	}
	
	private iControl.CommonIPPortDefinition[][] genEmptyMembersArray() {
		iControl.CommonIPPortDefinition[] membersInnerArray = new iControl.CommonIPPortDefinition[0];
		return new iControl.CommonIPPortDefinition[][]{membersInnerArray};
	}
	
	private LocalLBLBMethod[] genLbMethod(LbAlgorithm algorithm) {
		if (algorithm.getMethod() != null) {
			return new LocalLBLBMethod[]{algorithm.getMethod()};
		} else {
			return new LocalLBLBMethod[]{LbAlgorithm.RoundRobin.getMethod()};
		}
	}
	
	// Stats methods
	
	private ExternalNetworkResourceUsageAnswer getIpBytesSentAndReceived(ExternalNetworkResourceUsageCommand cmd) throws ExecutionException {
		ExternalNetworkResourceUsageAnswer answer = new ExternalNetworkResourceUsageAnswer(cmd);
		
		try {
			
			LocalLBVirtualServerVirtualServerStatistics stats = _virtualServerApi.get_all_statistics();
			for (LocalLBVirtualServerVirtualServerStatisticEntry entry : stats.getStatistics()) {
				String virtualServerIp = entry.getVirtual_server().getAddress();
				
				if (_inline) {
					virtualServerIp = stripRouteDomainFromAddress(virtualServerIp);
				}
				
				long[] bytesSentAndReceived = answer.ipBytes.get(virtualServerIp);
				
				if (bytesSentAndReceived == null) {
				    bytesSentAndReceived = new long[]{0, 0};
				}
								
				for (CommonStatistic stat : entry.getStatistics()) {	
					int index;
					if (stat.getType().equals(CommonStatisticType.STATISTIC_CLIENT_SIDE_BYTES_OUT)) {		
						// Add to the outgoing bytes
						index = 0;
					} else if (stat.getType().equals(CommonStatisticType.STATISTIC_CLIENT_SIDE_BYTES_IN)) {
						// Add to the incoming bytes
						index = 1;
					} else {
						continue;
					}
					
					long high = stat.getValue().getHigh(); 
					long low = stat.getValue().getLow(); 
					long full;
					long rollOver = 0x7fffffff + 1;
					
					if (high >= 0) { 
						full = (high << 32) & 0xffff0000; 
					} else {
						full = ((high & 0x7fffffff) << 32) + (0x80000000 << 32); 
					}

					if (low >= 0) {
						full += low;
					} else {
						full += (low & 0x7fffffff) + rollOver;
					}
					
					bytesSentAndReceived[index] += full;
				}
				
				if (bytesSentAndReceived[0] >= 0 && bytesSentAndReceived[1] >= 0) {
					answer.ipBytes.put(virtualServerIp, bytesSentAndReceived);			
				}
			}
		} catch (Exception e) {
			s_logger.error(e);
			throw new ExecutionException(e.getMessage());
		}
		
		return answer;
	}
	
	// Misc methods
	
	private String tagAddressWithRouteDomain(String address, long vlanTag) {
		return address + _routeDomainIdentifier + vlanTag;
	}
	
	private String stripRouteDomainFromAddress(String address) {
		int i = address.indexOf(_routeDomainIdentifier);
		
		if (i > 0) {
			address = address.substring(0, i);
		}
		
		return address;
	}
	
	private String genObjectName(Object... args) {
		String objectName = "";
		
		for (int i = 0; i < args.length; i++) {
			objectName += args[i];
			if (i != args.length -1) {
				objectName += _objectNamePathSep;
			}
		}
		
		return objectName;
	}	
	
	private long[] genLongArray(long l) {
		return new long[]{l};
	}
	
	private static String[] genStringArray(String s) {
		return new String[]{s};
	}				

}


	
	

	  