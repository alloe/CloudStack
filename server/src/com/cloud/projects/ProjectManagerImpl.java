/**
 * Copyright (C) 2011 Citrix Systems, Inc.  All rights reserved
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
package com.cloud.projects;

import java.io.UnsupportedEncodingException;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import javax.ejb.Local;
import javax.mail.Authenticator;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.URLName;
import javax.mail.internet.InternetAddress;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.acl.SecurityChecker.AccessType;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.projects.Project.State;
import com.cloud.projects.ProjectAccount.Role;
import com.cloud.projects.dao.ProjectAccountDao;
import com.cloud.projects.dao.ProjectDao;
import com.cloud.projects.dao.ProjectInvitationDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserContext;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.sun.mail.smtp.SMTPMessage;
import com.sun.mail.smtp.SMTPSSLTransport;
import com.sun.mail.smtp.SMTPTransport;

@Local(value = { ProjectService.class, ProjectManager.class })
public class ProjectManagerImpl implements ProjectManager, Manager{
    public static final Logger s_logger = Logger.getLogger(ProjectManagerImpl.class);
    private String _name;
    private EmailInvite _emailInvite;
    
    @Inject
    private DomainDao _domainDao;
    @Inject
    private ProjectDao _projectDao;
    @Inject
    AccountManager _accountMgr;
    @Inject
    DomainManager _domainMgr;
    @Inject
    ConfigurationManager _configMgr;  
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject
    private ProjectAccountDao _projectAccountDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private ConfigurationDao _configDao;
    @Inject
    private ProjectInvitationDao _projectInvitationDao;
    
    protected boolean _invitationRequired = false;
    protected long _invitationTimeOut = 86400;
    
    
    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _name = name;
        
        Map<String, String> configs = _configDao.getConfiguration(params);
        _invitationRequired = Boolean.valueOf(configs.get(Config.ProjectInviteRequired.key()));
        _invitationTimeOut = Long.valueOf(configs.get(Config.ProjectInvitationExpirationTime.key()));
        
        
        // set up the email system for project invitations

        String smtpHost = configs.get("project.smtp.host");
        int smtpPort = NumbersUtil.parseInt(configs.get("project.smtp.port"), 25);
        String useAuthStr = configs.get("project.smtp.useAuth");
        boolean useAuth = ((useAuthStr == null) ? false : Boolean.parseBoolean(useAuthStr));
        String smtpUsername = configs.get("project.smtp.username");
        String smtpPassword = configs.get("project.smtp.password");
        String emailSender = configs.get("project.email.sender");
        String smtpDebugStr = configs.get("project.smtp.debug");
        boolean smtpDebug = false;
        if (smtpDebugStr != null) {
            smtpDebug = Boolean.parseBoolean(smtpDebugStr);
        }

        _emailInvite = new EmailInvite(smtpHost, smtpPort, useAuth, smtpUsername, smtpPassword, emailSender, smtpDebug);
        
        return true;
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
    public String getName() {
        return _name;
    }
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_CREATE, eventDescription = "creating project", create=true)
    @DB
    public Project createProject(String name, String displayText, String accountName, Long domainId) throws ResourceAllocationException{
        Account caller = UserContext.current().getCaller();
        Account owner = caller;
        
        //Verify request parameters
        if ((accountName != null && domainId == null) || (domainId != null && accountName == null)) {
            throw new InvalidParameterValueException("Account name and domain id must be specified together");
        }
        
        if (accountName != null) {
            owner = _accountMgr.finalizeOwner(caller, accountName, domainId, null);
        }
        
        //don't allow 2 projects with the same name inside the same domain
        if (_projectDao.findByNameAndDomain(name, owner.getDomainId()) != null) {
            throw new InvalidParameterValueException("Project with name " + name + " already exists in domain id=" + owner.getDomainId());
        }
        
        //do resource limit check
        _resourceLimitMgr.checkResourceLimit(owner, ResourceType.project);
        
        Transaction txn = Transaction.currentTxn();
        txn.start();
        
        //Create an account associated with the project
        StringBuilder acctNm = new StringBuilder("PrjAcct-");
        acctNm.append(name).append("-").append(owner.getDomainId());
        
        Account projectAccount = _accountMgr.createAccount(acctNm.toString(), Account.ACCOUNT_TYPE_PROJECT, domainId, null);
        
        Project project = _projectDao.persist(new ProjectVO(name, displayText, owner.getDomainId(), projectAccount.getId()));
        
        //assign owner to the project
        assignAccountToProject(project, owner.getId(), ProjectAccount.Role.Owner);
        
        if (project != null) {
            UserContext.current().setEventDetails("Project id=" + project.getId());
        }
        
        //Increment resource count for the Owner's domain
        _resourceLimitMgr.incrementResourceCount(owner.getAccountId(), ResourceType.project);
        
        txn.commit();
        
        return project;
    }
    
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_CREATE, eventDescription = "creating project", async=true)
    @DB
    public Project enableProject(long projectId){
        Account caller = UserContext.current().getCaller();
        
        ProjectVO project= getProject(projectId);
        //verify input parameters
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find project by id " + projectId);
        }
        
        _accountMgr.checkAccess(caller,AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
        
        //at this point enabling project doesn't require anything, so just update the state
        project.setState(State.Active);
        _projectDao.update(projectId, project);
        
        return project;
    }
    
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_DELETE, eventDescription = "deleting project", async = true)
    @DB
    public boolean deleteProject (long projectId) {
        UserContext ctx = UserContext.current();
        Account caller = ctx.getCaller();
        
        ProjectVO project= getProject(projectId);
        //verify input parameters
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find project by id " + projectId);
        }
        
        _accountMgr.checkAccess(caller,AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
        
        //mark project as inactive first, so you can't add resources to it
        Transaction txn = Transaction.currentTxn();
        txn.start();
        s_logger.debug("Marking project id=" + projectId + " with state " + State.Disabled + " as a part of project delete...");
        project.setState(State.Disabled);
        boolean updateResult = _projectDao.update(projectId, project);
        _resourceLimitMgr.decrementResourceCount(project.getProjectAccountId(), ResourceType.project);
        txn.commit();
        
        if (updateResult) {
            if (!cleanupProject(project, _accountDao.findById(caller.getId()), ctx.getCallerUserId())) {
                s_logger.warn("Failed to cleanup project's id=" + projectId + " resources, not removing the project yet");
                return false;
            } else {
                return _projectDao.remove(projectId);
            }
        } else {
            s_logger.warn("Failed to mark the project id=" + projectId + " with state " + State.Disabled);
            return false;
        }  
    }
    
    @DB
    private boolean cleanupProject(Project project, AccountVO caller, Long callerUserId) {
        boolean result=true; 
        //Delete project's account
        AccountVO account = _accountDao.findById(project.getProjectAccountId());
        s_logger.debug("Deleting projects " + project + " internal account id=" + account.getId() + " as a part of project cleanup...");
        
        result = result && _accountMgr.deleteAccount(account, callerUserId, caller);
        
        if (result) {
            //Unassign all users from the project
            
            Transaction txn = Transaction.currentTxn();
            txn.start();
            
            s_logger.debug("Unassigning all accounts from project " + project + " as a part of project cleanup...");
            List<? extends ProjectAccount> projectAccounts = _projectAccountDao.listByProjectId(project.getId());
            for (ProjectAccount projectAccount : projectAccounts) {
                result = result && unassignAccountFromProject(projectAccount.getProjectId(), projectAccount.getAccountId());
            }
            
            s_logger.debug("Removing all invitations for the project " + project + " as a part of project cleanup...");
             _projectInvitationDao.cleanupInvitations(project.getId());
            
            txn.commit();
            if (result) {
                s_logger.debug("Accounts are unassign successfully from project " + project + " as a part of project cleanup...");
            }
        } else {
            s_logger.warn("Failed to cleanup project's internal account");
        }
        
        return result;
    }
    
    @Override
    public boolean unassignAccountFromProject(long projectId, long accountId) {
        ProjectAccountVO projectAccount = _projectAccountDao.findByProjectIdAccountId(projectId, accountId);
        if (projectAccount == null) {
            s_logger.debug("Account id=" + accountId + " is not assigned to project id=" + projectId + " so no need to unassign");
            return true;
        }
        
        if ( _projectAccountDao.remove(projectAccount.getId())) {
            return true;
        } else {
            s_logger.warn("Failed to unassign account id=" + accountId + " from the project id=" + projectId);
            return false;
        }
    }
    
    @Override
    public ProjectVO getProject (long projectId) {
        return _projectDao.findById(projectId);
    }
    
    @Override
    public List<? extends Project> listProjects(Long id, String name, String displayText, String state, String accountName, Long domainId, String keyword, Long startIndex, Long pageSize) {
        Account caller = UserContext.current().getCaller();
        Long accountId = null;
        String path = null;
        
        Filter searchFilter = new Filter(ProjectVO.class, "id", false, startIndex, pageSize);
        SearchBuilder<ProjectVO> sb = _projectDao.createSearchBuilder();
        
        if (_accountMgr.isAdmin(caller.getType())) {
            if (domainId != null) {
                DomainVO domain = _domainDao.findById(domainId);
                if (domain == null) {
                    throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist in the system");
                }

                _accountMgr.checkAccess(caller, domain);

                if (accountName != null) {
                    Account owner = _accountMgr.getActiveAccountByName(accountName, domainId);
                    if (owner == null) {
                        throw new InvalidParameterValueException("Unable to find account " + accountName + " in domain " + domainId);
                    }
                    accountId = owner.getId();
                }
            }
            
            if (caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
                DomainVO domain = _domainDao.findById(caller.getDomainId());
                path = domain.getPath();
            }
        } else {
            if (accountName != null && !accountName.equals(caller.getAccountName())) {
                throw new PermissionDeniedException("Can't list account " + accountName + " projects; unauthorized");
            }
            
            if (domainId != null && domainId.equals(caller.getDomainId())) {
                throw new PermissionDeniedException("Can't list domain id= " + domainId + " projects; unauthorized");
            }
            
            accountId = caller.getId();
        }
        
        if (path != null) {
            SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }
        
        SearchCriteria<ProjectVO> sc = sb.create();
        
        if (id != null) {
            sc.addAnd("id", Op.EQ, id);
        }
        
        if (name != null) {
            sc.addAnd("name", Op.EQ, name);
        }
        
        if (displayText != null) {
            sc.addAnd("displayText", Op.EQ, displayText);
        }
        
        if (accountId != null) {
            sc.addAnd("accountId", Op.EQ, accountId);
        }
        
        if (state != null) {
            sc.addAnd("state", Op.EQ, state);
        }
        
        if (keyword != null) {
            SearchCriteria<ProjectVO> ssc = _projectDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("displayText", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }
        
        if (path != null) {
            sc.setJoinParameters("domainSearch", "path", path);
        }
        
        return _projectDao.search(sc, searchFilter);
    }
    
    @Override
    public ProjectAccount assignAccountToProject(Project project, long accountId, ProjectAccount.Role accountRole) {
        return _projectAccountDao.persist(new ProjectAccountVO(project, accountId, accountRole));
    }
    
    @Override
    public boolean deleteAccountFromProject(long projectId, long accountId) {
        ProjectAccountVO projectAccount = _projectAccountDao.findByProjectIdAccountId(projectId, accountId);
        return _projectAccountDao.remove(projectAccount.getId());
    }
    
    @Override
    public Account getProjectOwner(long projectId) {
        long accountId = _projectAccountDao.getProjectOwner(projectId).getAccountId();
        return _accountMgr.getAccount(accountId);
    }
    
    @Override
    public ProjectVO findByProjectAccountId(long projectAccountId) {
        return _projectDao.findByProjectAccountId(projectAccountId);
    }
    
    @Override
    public Project findByNameAndDomainId(String name, long domainId) {
        return _projectDao.findByNameAndDomain(name, domainId);
    }
    
    @Override
    public boolean canAccessProjectAccount(Account caller, long accountId) {
        //ROOT admin always can access the project
        if (caller.getType() == Account.ACCOUNT_TYPE_ADMIN) {
            return true;
        } else if (caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
            Account owner = _accountMgr.getAccount(accountId);
            _accountMgr.checkAccess(caller, _domainDao.findById(owner.getDomainId()));
            return true;
        }
        
        return _projectAccountDao.canAccessProjectAccount(caller.getId(), accountId);
    }
    
    public boolean canModifyProjectAccount(Account caller, long accountId) {
        //ROOT admin always can access the project
        if (caller.getType() == Account.ACCOUNT_TYPE_ADMIN) {
            return true;
        } else if (caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
            Account owner = _accountMgr.getAccount(accountId);
            _accountMgr.checkAccess(caller, _domainDao.findById(owner.getDomainId()));
            return true;
        }
        return _projectAccountDao.canModifyProjectAccount(caller.getId(), accountId);
    }
    
    @Override @DB
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_UPDATE, eventDescription = "updating project", async=true)
    public Project updateProject(long projectId, String displayText, String newOwnerName) {
        Account caller = UserContext.current().getCaller();
        
        //check that the project exists
        ProjectVO project = getProject(projectId);
        
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find the project id=" + projectId);
        }
       
        //verify permissions
        _accountMgr.checkAccess(caller,AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
        
        Transaction txn = Transaction.currentTxn();
        txn.start();
        if (displayText != null) {
            project.setDisplayText(displayText);
            _projectDao.update(projectId, project);
        }
        
        if (newOwnerName != null) {
            //check that the new owner exists
            Account futureOwnerAccount = _accountMgr.getActiveAccountByName(newOwnerName, project.getDomainId());
            if (futureOwnerAccount == null) {
                throw new InvalidParameterValueException("Unable to find account name=" + newOwnerName + " in domain id=" + project.getDomainId());
            }
            Account currentOwnerAccount = getProjectOwner(projectId);
            if (currentOwnerAccount.getId() != futureOwnerAccount.getId()) {
                ProjectAccountVO futureOwner = _projectAccountDao.findByProjectIdAccountId(projectId, futureOwnerAccount.getAccountId());
                if (futureOwner == null) {
                    throw new InvalidParameterValueException("Account " + newOwnerName + " doesn't belong to the project. Add it to the project first and then change the project's ownership");
                }
                
                //unset the role for the old owner
                ProjectAccountVO currentOwner = _projectAccountDao.findByProjectIdAccountId(projectId, currentOwnerAccount.getId());
                currentOwner.setAccountRole(Role.Regular);
                _projectAccountDao.update(currentOwner.getId(), currentOwner);
                
                //set new owner
                futureOwner.setAccountRole(Role.Owner);
                _projectAccountDao.update(futureOwner.getId(), futureOwner);
                
            } else {
                s_logger.trace("Future owner " + newOwnerName + "is already the owner of the project id=" + projectId);
            }
        }
        
        txn.commit();
        
        return _projectDao.findById(projectId);
        
    }
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_ACCOUNT_ADD, eventDescription = "adding account to project", async=true)
    public boolean addAccountToProject(long projectId, String accountName, String email) {
        Account caller = UserContext.current().getCaller();
        
        //check that the project exists
        Project project = getProject(projectId);
        
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find the project id=" + projectId);
        }
       
        //check that account-to-add exists
        Account account = null;
        if (accountName != null) {
            account = _accountMgr.getActiveAccountByName(accountName, project.getDomainId());
            if (account == null) {
                throw new InvalidParameterValueException("Unable to find account name=" + accountName + " in domain id=" + project.getDomainId());
            }
            
            //verify permissions - only project owner can assign
            _accountMgr.checkAccess(caller, AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
            
            //Check if the account already added to the project
            ProjectAccount projectAccount =  _projectAccountDao.findByProjectIdAccountId(projectId, account.getId());
            if (projectAccount != null) {
                s_logger.debug("Account " + accountName + " already added to the project id=" + projectId);
                return true;
            }
        }
        
        if (_invitationRequired) {
            return inviteAccountToProject(project, account, email);
        } else {
            if (account == null) {
                throw new InvalidParameterValueException("Account information is required for assigning account to the project");
            }
            if (assignAccountToProject(project, account.getId(), ProjectAccount.Role.Regular) != null) {
                return true;
            } else {
                s_logger.warn("Failed to add account " + accountName + " to project id=" + projectId);
                return false;
            }
        }
    }
    
    private boolean inviteAccountToProject(Project project, Account account, String email) {
        if (account != null) {
            if (createAccountInvitation(project, account.getId()) != null) {
                return true;
            } else {
                s_logger.warn("Failed to generate invitation for account " + account.getAccountName() + " to project id=" + project);
                return false;
            } 
        }
      
        if (email != null) {
            //generate the token
            String token = generateToken(10);
            if (generateTokenBasedInvitation(project, email, token) != null) {
                return true;
            } else {
              s_logger.warn("Failed to generate invitation for email " + email + " to project id=" + project);
              return false;
            } 
        }
        
        return false;
    }
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_ACCOUNT_REMOVE, eventDescription = "removing account from project", async=true)
    public boolean deleteAccountFromProject(long projectId, String accountName) {
        Account caller = UserContext.current().getCaller();
        
        //check that the project exists
        Project project = getProject(projectId);
        
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find the project id=" + projectId);
        }
       
        //check that account-to-remove exists
        Account account = _accountMgr.getActiveAccountByName(accountName, project.getDomainId());
        if (account == null) {
            throw new InvalidParameterValueException("Unable to find account name=" + accountName + " in domain id=" + project.getDomainId());
        }
        
        //verify permissions
        _accountMgr.checkAccess(caller,AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
        
        //Check if the account exists in the project
        ProjectAccount projectAccount =  _projectAccountDao.findByProjectIdAccountId(projectId, account.getId());
        if (projectAccount == null) {
            throw new InvalidParameterValueException("Account " + accountName + " is not assigned to the project id=" + projectId);
        }
        
        //can't remove the owner of the project
        if (projectAccount.getAccountRole() == Role.Owner) {
            throw new InvalidParameterValueException("Unable to delete account " + accountName + " from the project id=" + projectId + " as the account is the owner of the project");
        }
        
        return deleteAccountFromProject(projectId, account.getId());
    }
    
    
    @Override
    public List<? extends ProjectAccount> listProjectAccounts(long projectId, String accountName, String role, Long startIndex, Long pageSizeVal) {
        Account caller = UserContext.current().getCaller();
        
        //check that the project exists
        Project project = getProject(projectId);
        
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find the project id=" + projectId);
        }
        
        //verify permissions
        _accountMgr.checkAccess(caller, _domainDao.findById(project.getDomainId()));
        
        Filter searchFilter = new Filter(ProjectAccountVO.class, "id", false, startIndex, pageSizeVal);
        SearchBuilder<ProjectAccountVO> sb = _projectAccountDao.createSearchBuilder();
        sb.and("accountRole", sb.entity().getAccountRole(), Op.EQ);
        
        SearchBuilder<AccountVO> accountSearch;
        if (accountName != null) {
            accountSearch = _accountDao.createSearchBuilder();
            accountSearch.and("accountName", accountSearch.entity().getAccountName(), SearchCriteria.Op.EQ);
            sb.join("accountSearch", accountSearch, sb.entity().getAccountId(), accountSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }
        
        SearchCriteria<ProjectAccountVO> sc = sb.create();
        
        if (role != null) {
            sc.setParameters("accountRole", role);
        }
        
        if (accountName != null) {
            sc.setJoinParameters("accountSearch", "accountName", accountName);
        }
        
        return _projectAccountDao.search(sc, searchFilter);
    }
    
    public ProjectInvitation createAccountInvitation(Project project, Long accountId) {
        //verify if the invitation was already generated
        ProjectInvitationVO invite = _projectInvitationDao.findPendingByAccountIdProjectId(accountId, project.getId());
        
        if (invite != null) {
            if (_projectInvitationDao.isActive(invite.getId(), _invitationTimeOut)) {
                throw new InvalidParameterValueException("There is already a pending invitation for account id=" + accountId + " to the project id=" + project);
            } else {
                if (invite.getState() == ProjectInvitation.State.Pending) {
                    expireInvitation(invite);
                }
            }
        }
        
        return _projectInvitationDao.persist(new ProjectInvitationVO(project.getId(), accountId, project.getDomainId(), null, null));
    }
    
    public ProjectInvitation generateTokenBasedInvitation(Project project, String email, String token) {
        //verify if the invitation was already generated
        ProjectInvitationVO invite = _projectInvitationDao.findPendingByEmailAndProjectId(email, project.getId());
        
        if (invite != null) {
            if (_projectInvitationDao.isActive(invite.getId(), _invitationTimeOut)) {
                throw new InvalidParameterValueException("There is already a pending invitation for email=" + email + " to the project id=" + project);
            } else {
                if (invite.getState() == ProjectInvitation.State.Pending) {
                    expireInvitation(invite);
                }
            }
        }
        
        ProjectInvitation projectInvitation = _projectInvitationDao.persist(new ProjectInvitationVO(project.getId(), null, project.getDomainId(), email, token));
        try {
            _emailInvite.sendInvite(token, email, project.getId());
        } catch (Exception ex){
            s_logger.warn("Failed to send project id=" + project + " invitation to the email " + email + "; removing the invitation record from the db", ex);
            _projectInvitationDao.remove(projectInvitation.getId());
            return null;
        }
        
        return projectInvitation;
    }
    
    private boolean expireInvitation(ProjectInvitationVO invite) {
        s_logger.debug("Expiring invitation id=" + invite.getId());
        invite.setState(ProjectInvitation.State.Expired);
        return _projectInvitationDao.update(invite.getId(), invite);
    }
    
    @Override
    public List<? extends ProjectInvitation> listProjectInvitations(Long id, Long projectId, String accountName, Long domainId, String state, boolean activeOnly, Long startIndex, Long pageSizeVal) {
        Account caller = UserContext.current().getCaller();
        Long accountId = null;
        String domainPath = null;
        
        if (caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
            if (domainId == null) {
                domainPath = _domainMgr.getDomain(caller.getDomainId()).getPath();
            } 
        } else if (caller.getType() == Account.ACCOUNT_TYPE_NORMAL){
            // regular user is constraint to only his account
            accountId = caller.getId();
        }
        
        if (domainId != null) {
            Domain domain = _domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist");
            }
            _accountMgr.checkAccess(caller, domain);

            if (accountName != null) {
                Account account = _accountDao.findActiveAccount(accountName, domainId);
                if (account == null) {
                    throw new InvalidParameterValueException("Unable to find account by name " + accountName + " in domain " + domainId);
                }

                _accountMgr.checkAccess(caller, null, account);
                accountId = account.getId();
            }
        }
        
        Filter searchFilter = new Filter(ProjectInvitationVO.class, "id", true, startIndex, pageSizeVal);
        
        SearchBuilder<ProjectInvitationVO> sb = _projectInvitationDao.createSearchBuilder();
        sb.and("accountId", sb.entity().getAccountId(), SearchCriteria.Op.EQ);
        sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.EQ);
        sb.and("projectId", sb.entity().getProjectId(), SearchCriteria.Op.EQ);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("created", sb.entity().getCreated(), SearchCriteria.Op.GT);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);

        if (domainPath != null) {
            // do a domain LIKE match for the admin case if isRecursive is true
            SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }
        
        SearchCriteria<ProjectInvitationVO> sc = sb.create();
        
        if (domainPath != null) {
            sc.setJoinParameters("domainSearch", "path", domainPath);
        }
        
        if (accountId != null) {
            sc.setParameters("accountId", accountId);
        }
        
        if (projectId != null){
            sc.setParameters("projectId", projectId);
        }
        
        if (state != null) {
            sc.setParameters("state", state);
        }
        
        if (id != null) {
            sc.setParameters("id", id);
        }
        
        if (activeOnly) {
            sc.setParameters("state", ProjectInvitation.State.Pending);
            sc.setParameters("created", new Date((System.currentTimeMillis() >> 10) - _invitationTimeOut));
        }
        
        return _projectInvitationDao.search(sc, searchFilter);
    }
    
    @Override @DB
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_INVITATION_UPDATE, eventDescription = "updating project invitation", async=true)
    public boolean updateInvitation(long projectId, String accountName, String token, boolean accept) {
        Account caller = UserContext.current().getCaller();
        Long accountId = null;
        boolean result = true;
        
        //if accountname and token are null, default accountname to caller's account name
        if (accountName == null && token == null) {
            accountName = caller.getAccountName();
        }
        
        //check that the project exists
        Project project = getProject(projectId);
        
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find the project id=" + projectId);
        }
        
        if (accountName != null) {
            //check that account-to-remove exists
            Account account = _accountMgr.getActiveAccountByName(accountName, project.getDomainId());
            if (account == null) {
                throw new InvalidParameterValueException("Unable to find account name=" + accountName + " in domain id=" + project.getDomainId());
            }
            
            //verify permissions
            _accountMgr.checkAccess(caller, null, account);
            
            accountId = account.getId();
        } else {
            accountId = caller.getId();
        }
        
        //check that invitation exists
        ProjectInvitationVO invite = null;
        if (token == null) {
            invite = _projectInvitationDao.findPendingByAccountIdProjectId(accountId, projectId);
        } else {
            invite = _projectInvitationDao.findPendingByTokenAndProjectId(token, projectId);
        }
        
        if (invite != null) {
            if (!_projectInvitationDao.isActive(invite.getId(), _invitationTimeOut) && accept) {
                expireInvitation(invite);
                throw new InvalidParameterValueException("Invitation is expired for account id=" + accountName + " to the project id=" + projectId);
            } else {
                Transaction txn = Transaction.currentTxn();
                txn.start();
                
                ProjectInvitation.State newState = accept ? ProjectInvitation.State.Completed : ProjectInvitation.State.Declined;
                
               //update invitation
               s_logger.debug("Marking invitation " + invite + " with state " + newState);
               invite.setState(newState);
               result = _projectInvitationDao.update(invite.getId(), invite);
               
               if (result && accept) {
                   //check if account already exists for the project (was added before invitation got accepted)
                   ProjectAccount projectAccount =  _projectAccountDao.findByProjectIdAccountId(projectId, accountId);
                   if (projectAccount != null) {
                       s_logger.debug("Account " + accountName + " already added to the project id=" + projectId);
                   } else {
                       assignAccountToProject(project, accountId, ProjectAccount.Role.Regular); 
                   }
               } else {
                   s_logger.warn("Failed to update project invitation " + invite + " with state " + newState);
               }
              
               txn.commit();
            }
        } else {
            throw new InvalidParameterValueException("Unable to find invitation for account name=" + accountName + " to the project id=" + projectId);
        }
        
        return result;
    }
    
    @Override
    public List<Long> listPermittedProjectAccounts(long accountId) {
        return _projectAccountDao.listPermittedAccountIds(accountId);
    }
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_ACTIVATE, eventDescription = "activating project")
    public Project activateProject(long projectId) {
        Account caller = UserContext.current().getCaller();
        
        //check that the project exists
        ProjectVO project = getProject(projectId);
        
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find the project id=" + projectId);
        }
       
        //verify permissions
        _accountMgr.checkAccess(caller,AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
        
        //allow project activation only when it's in Suspended state
        Project.State currentState = project.getState();
        
        if (currentState == State.Active) {
            s_logger.debug("The project id=" + projectId + " is already active, no need to activate it again");
            return project;
        } 
        
        if (currentState != State.Suspended) {
            throw new InvalidParameterValueException("Can't activate the project in " + currentState + " state");
        }
        
        project.setState(Project.State.Active);
        _projectDao.update(projectId, project);
        
        return _projectDao.findById(projectId);
    }
    
    
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_SUSPEND, eventDescription = "suspending project", async = true)
    public Project suspendProject (long projectId) throws ConcurrentOperationException, ResourceUnavailableException {
        Account caller = UserContext.current().getCaller();
        
        ProjectVO project= getProject(projectId);
        //verify input parameters
        if (project == null) {
            throw new InvalidParameterValueException("Unable to find project by id " + projectId);
        }
        
        _accountMgr.checkAccess(caller,AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
        
        if (suspendProject(project)) {
            s_logger.debug("Successfully suspended project id=" + projectId);
            return _projectDao.findById(projectId);
        } else {
            throw new CloudRuntimeException("Failed to suspend project id=" + projectId);
        }
        
    }
    
    private boolean suspendProject(ProjectVO project) throws ConcurrentOperationException, ResourceUnavailableException{
        s_logger.debug("Marking project " + project + " with state " + State.Suspended + " as a part of project suspend...");
        project.setState(State.Suspended);
        boolean updateResult = _projectDao.update(project.getId(), project);
        
        if (updateResult) {
            long projectAccountId = project.getProjectAccountId();
            if (!_accountMgr.disableAccount(projectAccountId)) {
                s_logger.warn("Failed to suspend all project's " + project + " resources; the resources will be suspended later by background thread");
            } 
        } else {
            throw new CloudRuntimeException("Failed to mark the project " + project + " with state " + State.Suspended);
        }
        return true;
    }
    
    
    public static String generateToken(int length) {
        String charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Random rand = new Random(System.currentTimeMillis());
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int pos = rand.nextInt(charset.length());
            sb.append(charset.charAt(pos));
        }
        return sb.toString();
    }
    
    class EmailInvite {
        private Session _smtpSession;
        private final String _smtpHost;
        private int _smtpPort = -1;
        private boolean _smtpUseAuth = false;
        private final String _smtpUsername;
        private final String _smtpPassword;
        private final String _emailSender;

        public EmailInvite(String smtpHost, int smtpPort, boolean smtpUseAuth, final String smtpUsername, final String smtpPassword, String emailSender, boolean smtpDebug) {
            _smtpHost = smtpHost;
            _smtpPort = smtpPort;
            _smtpUseAuth = smtpUseAuth;
            _smtpUsername = smtpUsername;
            _smtpPassword = smtpPassword;
            _emailSender = emailSender;

            if (_smtpHost != null) {
                Properties smtpProps = new Properties();
                smtpProps.put("mail.smtp.host", smtpHost);
                smtpProps.put("mail.smtp.port", smtpPort);
                smtpProps.put("mail.smtp.auth", ""+smtpUseAuth);
                if (smtpUsername != null) {
                    smtpProps.put("mail.smtp.user", smtpUsername);
                }

                smtpProps.put("mail.smtps.host", smtpHost);
                smtpProps.put("mail.smtps.port", smtpPort);
                smtpProps.put("mail.smtps.auth", "" + smtpUseAuth);
                if (smtpUsername != null) {
                    smtpProps.put("mail.smtps.user", smtpUsername);
                }

                if ((smtpUsername != null) && (smtpPassword != null)) {
                    _smtpSession = Session.getInstance(smtpProps, new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(smtpUsername, smtpPassword);
                        }
                    });
                } else {
                    _smtpSession = Session.getInstance(smtpProps);
                }
                _smtpSession.setDebug(smtpDebug);
            } else {
                _smtpSession = null;
            }
        }

        public void sendInvite(String token, String email, long projectId) throws MessagingException, UnsupportedEncodingException {  
            if (_smtpSession != null) {
                InternetAddress address = null;
                if (email != null) {
                    try {
                        address= new InternetAddress(email, email);
                    } catch (Exception ex) {
                        s_logger.error("Exception creating address for: " + email, ex);
                    }
                }
                
                String content = "You've been invited to join the CloudStack project id=" + projectId + ". Please use token " + token + " to complete registration";
                
                SMTPMessage msg = new SMTPMessage(_smtpSession);
                msg.setSender(new InternetAddress(_emailSender, _emailSender));
                msg.setFrom(new InternetAddress(_emailSender, _emailSender));
                msg.addRecipient(RecipientType.TO, address);
                msg.setSubject("You are invited to join the cloud stack project id=" + projectId);
                msg.setSentDate(new Date(System.currentTimeMillis() >> 10));
                msg.setContent(content, "text/plain");
                msg.saveChanges();

                SMTPTransport smtpTrans = null;
                if (_smtpUseAuth) {
                    smtpTrans = new SMTPSSLTransport(_smtpSession, new URLName("smtp", _smtpHost, _smtpPort, null, _smtpUsername, _smtpPassword));
                } else {
                    smtpTrans = new SMTPTransport(_smtpSession, new URLName("smtp", _smtpHost, _smtpPort, null, _smtpUsername, _smtpPassword));
                }
                smtpTrans.connect();
                smtpTrans.sendMessage(msg, msg.getAllRecipients());
                smtpTrans.close();
            }
        }
    }
    
    
    @Override @DB
    @ActionEvent(eventType = EventTypes.EVENT_PROJECT_INVITATION_REMOVE, eventDescription = "removing project invitation", async=true)
    public boolean deleteProjectInvitation(long id) {
        Account caller = UserContext.current().getCaller();
        
        ProjectInvitation invitation = _projectInvitationDao.findById(id);
        if (invitation == null) {
            throw new InvalidParameterValueException("Unable to find project invitation by id " + id);
        }
        
        //check that the project exists
        Project project = getProject(invitation.getProjectId());
        
        //check permissions - only project owner can remove the invitations
        _accountMgr.checkAccess(caller, AccessType.ModifyProject, _accountMgr.getAccount(project.getProjectAccountId()));
        
        if (_projectInvitationDao.remove(id)) {
            s_logger.debug("Project Invitation id=" + id + " is removed");
            return true;
        } else {
            s_logger.debug("Failed to remove project invitation id=" + id);
            return false; 
        }
    }
}
