package com.dotmarketing.portlets.files.action;

import static com.dotmarketing.business.PermissionAPI.PERMISSION_CAN_ADD_CHILDREN;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionMapping;

import com.dotmarketing.beans.Host;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.business.web.WebAPILocator;
import com.dotmarketing.cache.StructureCache;
import com.dotmarketing.cache.WorkingCache;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.exception.WebAssetException;
import com.dotmarketing.factories.PublishFactory;
import com.dotmarketing.factories.WebAssetFactory;
import com.dotmarketing.portal.struts.DotPortletAction;
import com.dotmarketing.portlets.contentlet.business.ContentletAPI;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.fileassets.business.FileAssetAPI;
import com.dotmarketing.portlets.files.business.FileAPI;
import com.dotmarketing.portlets.files.model.File;
import com.dotmarketing.portlets.files.struts.FileForm;
import com.dotmarketing.portlets.folders.business.FolderAPI;
import com.dotmarketing.portlets.folders.model.Folder;
import com.dotmarketing.util.InodeUtils;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.util.WebKeys;
import com.liferay.portal.language.LanguageUtil;
import com.liferay.portal.model.User;
import com.liferay.portal.struts.ActionException;
import com.liferay.portal.util.Constants;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portlet.ActionRequestImpl;
import com.liferay.util.ParamUtil;
import com.liferay.util.servlet.SessionMessages;
import com.liferay.util.servlet.UploadPortletRequest;

/**
 * @author Maria
 * @author David H Torres 2009
 */

public class UploadMultipleFilesAction extends DotPortletAction {


	public void processAction(
			 ActionMapping mapping, ActionForm form, PortletConfig config,
			 ActionRequest req, ActionResponse res)
		 throws Exception {

        String cmd = req.getParameter(Constants.CMD);
		String referer = req.getParameter("referer");

		//wraps request to get session object
		ActionRequestImpl reqImpl = (ActionRequestImpl)req;
		HttpServletRequest httpReq = reqImpl.getHttpServletRequest();

		if ((referer!=null) && (referer.length()!=0)) {
			referer = URLDecoder.decode(referer,"UTF-8");
		}
		Logger.debug(this, "UploadMultipleFilesAction cmd=" + cmd);

        HibernateUtil.startTransaction();

		User user = _getUser(req);

		try {
			Logger.debug(this, "Calling Retrieve method");
			_retrieveWebAsset(req, res, config, form, user, File.class, WebKeys.FILE_EDIT);

		} catch (Exception ae) {
			_handleException(ae, req);
			return;
		}

        try {
            Logger.debug(this, "Calling Edit Method");
			_editWebAsset(req, res, config, form, user);

        }
        catch (Exception e) {

        }

        if ((cmd != null) && cmd.equals(Constants.ADD)) {
            try {

                Logger.debug(this, "Calling Save Method");

				String subcmd = req.getParameter("subcmd");

				_saveFileAsset(req, res, config, form, user, subcmd);

				_sendToReferral(req,res,referer);

            } catch (ActionException ae) {
				_handleException(ae, req);
				if (ae.getMessage().equals("message.file_asset.error.filename.exists")) {
					_sendToReferral(req,res,referer);
				}
				else if (ae.getMessage().equals(WebKeys.USER_PERMISSIONS_EXCEPTION)) {
					SessionMessages.add(httpReq, "error", "message.insufficient.permissions.to.save");
					_sendToReferral(req,res,referer);
				}
            }

        }
        Logger.debug(this, "Unspecified Action");
        HibernateUtil.commitTransaction();

		setForward(req, "portlet.ext.files.upload_multiple");
    }

	public void _editWebAsset(ActionRequest req, ActionResponse res,PortletConfig config,ActionForm form, User user)
	throws Exception {

		FileAPI fileAPI = APILocator.getFileAPI();
		FolderAPI folderAPI = APILocator.getFolderAPI();

		// calls edit method from super class that returns parent folder
		super._editWebAsset(req, res, config, form, user, WebKeys.FILE_EDIT);

		// This can't be done on the WebAsset so it needs to be done here.
		File file = (File) req.getAttribute(WebKeys.FILE_EDIT);

       Folder parentFolder = null;

		if(req.getParameter("parent") != null) {
			parentFolder = folderAPI.find(req.getParameter("parent"),user,false);
		} else {
			parentFolder = fileAPI.getFileFolder(file,WebAPILocator.getHostWebAPI().getCurrentHost(req), user, false);
		}

		// setting parent folder path and inode on the form bean
		if(parentFolder != null) {
			FileForm cf = (FileForm) form;
			cf.setSelectedparent(parentFolder.getName());
			cf.setParent(parentFolder.getInode());
			cf.setSelectedparentPath(APILocator.getIdentifierAPI().find(parentFolder).getPath());
			file.setParent(parentFolder.getInode());
		}



		req.setAttribute("PARENT_FOLDER",parentFolder);

	}

	private String checkMACFileName(String fileName)
	{
		if (UtilMethods.isSet(fileName)) {
    		if (fileName.contains("/"))
    			fileName = fileName.substring(fileName.lastIndexOf("/") + 1, fileName.length());
   		 	if (fileName.contains("\\"))
    		 	fileName = fileName.substring(fileName.lastIndexOf("\\") + 1, fileName.length());
    		fileName = fileName.replaceAll("'","");
    	}
		return fileName;
	}

	private String getFriendlyName(String fileName){

		String val = fileName;
		boolean test = false;
		String newVal = "";
		for(int i=0 ; i < val.length() ; i++){
			String c =     val.substring(i,i+1);
			 if(c == "_") {
			 	  c = " ";
			}
			if(test == true || i == 0){
				 test = false;
			     c = c.toUpperCase();
			}
			if(c == " "){
             	 test = true;
			}


		    if(c == ".") break;
			newVal = newVal +   c;
		}

		return newVal;
	}

	public void _saveFileAsset(ActionRequest req, ActionResponse res,PortletConfig config,ActionForm form, User user, String subcmd)
	throws WebAssetException, Exception {

		try {
		    boolean isAdmin = com.dotmarketing.business.APILocator.getRoleAPI().doesUserHaveRole(user,com.dotmarketing.business.APILocator.getRoleAPI().loadCMSAdminRole());
		    
		    com.liferay.portlet.RenderRequestImpl reqImpl = (com.liferay.portlet.RenderRequestImpl) req;
	        HttpServletRequest httpReq = reqImpl.getHttpServletRequest();
	        HttpSession session = httpReq.getSession();
		    
			UploadPortletRequest uploadReq = PortalUtil.getUploadPortletRequest(req);

			String parent = ParamUtil.getString(req, "parent");

			//parent folder
			Folder folder = (Folder) APILocator.getFolderAPI().find(parent, user, false);
			
			String hostId=folder.getHostId();
			boolean isRootHost=APILocator.getFolderAPI().findSystemFolder().equals(folder);
			if(isRootHost)
			    hostId=(String)session.getAttribute(com.dotmarketing.util.WebKeys.CMS_SELECTED_HOST_ID);

			Host host=APILocator.getHostAPI().find(hostId, user, false);
			
			//check permissions
			if(isRootHost) {
			    if(!APILocator.getPermissionAPI().doesUserHavePermission(host, PERMISSION_CAN_ADD_CHILDREN, user))
			        throw new ActionException(WebKeys.USER_PERMISSIONS_EXCEPTION);
			}
			else {
			    _checkUserPermissions(folder, user, PERMISSION_CAN_ADD_CHILDREN);
			}

			String fileNamesStr = ParamUtil.getString(req, "fileNames");
			if(!UtilMethods.isSet(fileNamesStr))
				throw new ActionException(LanguageUtil.get(user, "message.file_asset.alert.please.upload"));

			String selectedStructureInode = ParamUtil.getString(req, "selectedStructure");
			if(!UtilMethods.isSet(selectedStructureInode))
				selectedStructureInode = StructureCache.getStructureByVelocityVarName(FileAssetAPI.DEFAULT_FILE_ASSET_STRUCTURE_VELOCITY_VAR_NAME).getInode();

			String[] fileNamesArray = fileNamesStr.split(WebKeys.CONTENTLET_FORM_NAME_VALUE_SEPARATOR);
			String customMessage = LanguageUtil.get(user, "message.file_asset.error.filename.filters"+" : ");
			if(fileNamesArray.length > 2)
				SessionMessages.add(req, "custommessage", LanguageUtil.get(user, "message.contentlets.batch.reindexing.background"));
			boolean filterError = false;

			ContentletAPI conAPI = APILocator.getContentletAPI();
			List<String> existingFileNames = new ArrayList<String>();
			for (int k=0;k<fileNamesArray.length;k++) {

				Contentlet contentlet = new Contentlet();
				contentlet.setStructureInode(selectedStructureInode);
				contentlet.setHost(hostId);
				contentlet.setFolder(folder.getInode());
				String fileName = fileNamesArray[k];
				String title = getFriendlyName(fileName);

				fileName = checkMACFileName(fileName);

				if(!APILocator.getFolderAPI().matchFilter(folder,fileName))
	            {
				   customMessage += fileName + ", ";
	               filterError = true;
	               continue;
	            }

				if (fileName.length()>0) {

					//checks if another identifier with the same name exists in the same folder
					if (APILocator.getFileAssetAPI().fileNameExists(host, folder, fileName, "")) {
						existingFileNames.add(fileName);
					}
					else {
						//sets filename for this new file

						contentlet.setStringProperty("title", title);
						contentlet.setStringProperty("fileName", fileName);
						java.io.File uploadedFile = uploadReq.getFile(fileName);
						contentlet.setBinary("fileAsset", uploadedFile);
						try {
							contentlet = APILocator.getContentletAPI().checkin(contentlet, user, false);
							if ((subcmd != null) && subcmd.equals(com.dotmarketing.util.Constants.PUBLISH)) {
							    if(isRootHost && !APILocator.getPermissionAPI().doesUserHaveInheriablePermissions(
							             host,  com.dotmarketing.portlets.files.model.File.class.getCanonicalName(), 
							             PermissionAPI.PERMISSION_PUBLISH, user) && !isAdmin)
							        throw new ActionException(WebKeys.USER_PERMISSIONS_EXCEPTION);
								APILocator.getVersionableAPI().setLive(contentlet);
							}
							/*
							HibernateUtil.commitTransaction();

							if(InodeUtils.isSet(contentlet.getInode()) && !conAPI.isInodeIndexed(contentlet.getInode())){
								Logger.error(this, "Timed Out waiting for index to return");
							}
							*/
						} catch (Exception e) {
							Logger.error(this, e.getMessage());
							SessionMessages.add(req, "error", e.getMessage());
						}
					}

				}
			}

			if(!existingFileNames.isEmpty()){
				StringBuffer messageText = new StringBuffer();
				if(existingFileNames.size()>1){
					messageText.append("The uploaded files ");
				}else{
					messageText.append("The uploaded file ");
				}

				for(int i=0;i<existingFileNames.size();i++){
					if(i==0){
						messageText.append(existingFileNames.get(i));
					}else{
						messageText.append(", "  + existingFileNames.get(i));
					}
				}
				if(existingFileNames.size()>1){
					messageText.append(" already exist in this folder");
				}else{
					messageText.append(" already exists in this folder");
				}

				SessionMessages.add(req, "custommessage", messageText.toString());
			}


			if(filterError)
			{
				customMessage = customMessage.substring(0,customMessage.lastIndexOf(","));
				SessionMessages.add(req, "custommessage",customMessage);
			}

		}
		catch (IOException e) {
			Logger.error(this, "Exception saving file: " + e.getMessage());
			throw new ActionException(e.getMessage());
		}
}
}