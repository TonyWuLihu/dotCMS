package com.dotcms.csspreproc;

import java.io.File;
import java.net.URL;

import javax.servlet.http.HttpServletRequest;

import com.dotcms.repackage.commons_io.org.apache.commons.io.FileUtils;
import com.dotcms.repackage.commons_io.org.apache.commons.io.IOUtils;
import com.dotcms.repackage.junit.org.junit.Assert;
import com.dotcms.repackage.junit.org.junit.Before;
import com.dotcms.repackage.junit.org.junit.Test;
import com.dotmarketing.beans.Host;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.CacheLocator;
import com.dotmarketing.cache.StructureCache;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.fileassets.business.FileAssetAPI;
import com.dotmarketing.portlets.folders.model.Folder;
import com.dotmarketing.servlets.test.ServletTestRunner;
import com.dotmarketing.util.UUIDGenerator;
import com.liferay.portal.model.User;

public class SassCompilerTest {
    
    protected String baseURL=null;
    
    @Before
    public void prepare() throws Exception {
        HttpServletRequest req=ServletTestRunner.localRequest.get();
        baseURL = "http://"+req.getServerName()+":"+req.getServerPort();
    }
    
    @Test
    public void case01() throws Exception {
        final String runId =  UUIDGenerator.generateUuid() ;
        final File tmpDir = new File(APILocator.getFileAPI().getRealAssetPathTmpBinary() + 
                File.separator + runId + File.separator + "sass01"); 
        tmpDir.mkdirs();
        
        final File screenFile = new File(tmpDir, "screen.scss");
        FileUtils.copyURLToFile(SassCompilerTest.class.getResource("sass01/screen.scss"), screenFile);
        
        final File layoutFile = new File(tmpDir, "_layout.scss");
        FileUtils.copyURLToFile(SassCompilerTest.class.getResource("sass01/_layout.scss"), layoutFile);
        
        final File stylesFile = new File(tmpDir, "_styles.scss");
        FileUtils.copyURLToFile(SassCompilerTest.class.getResource("sass01/_styles.scss"), stylesFile);
        
        final String expectedOutput = IOUtils.toString(SassCompilerTest.class.getResourceAsStream("sass01/screen.css"),"UTF-8");
        
        final User sysuser = APILocator.getUserAPI().getSystemUser();
        final Host demo = APILocator.getHostAPI().findByName("demo.dotcms.com", sysuser, false);
        final Folder folder = APILocator.getFolderAPI().createFolders("/"+runId, demo, sysuser, false);
        
        for(File f : new File[] {screenFile,layoutFile,stylesFile}) {
            Contentlet asset = new Contentlet();
            asset.setHost(demo.getIdentifier());
            asset.setFolder(folder.getInode());
            asset.setLanguageId(APILocator.getLanguageAPI().getDefaultLanguage().getId());
            asset.setStructureInode(StructureCache.getStructureByVelocityVarName(FileAssetAPI.DEFAULT_FILE_ASSET_STRUCTURE_VELOCITY_VAR_NAME).getInode());
            asset.setBinary(FileAssetAPI.BINARY_FIELD, f);
            asset.setStringProperty(FileAssetAPI.TITLE_FIELD, f.getName());
            asset = APILocator.getContentletAPI().checkin(asset,sysuser,false);
            APILocator.getContentletAPI().publish(asset, sysuser, false);
            APILocator.getContentletAPI().isInodeIndexed(asset.getInode(),true);
        }
        
        URL cssURL = new URL(baseURL + "/DOTSASS/" + runId + "/screen.css");
        
        long tt1 = System.currentTimeMillis();
        String response =  IOUtils.toString(cssURL.openStream(),"UTF-8");
        tt1 = System.currentTimeMillis() - tt1;
        
        Assert.assertEquals(expectedOutput.trim(), response.trim());
        
        // now it should take less time as its in cache now
        for(int x=0; x<10; x++) {
            long ttx = System.currentTimeMillis();
            response =  IOUtils.toString(cssURL.openStream(),"UTF-8");
            ttx = System.currentTimeMillis() - ttx;
            
            Assert.assertTrue(ttx < (tt1/10));
        }
        
        // now lets modify a bit one of the imported files and check if the resulting file reflects the change 
        final File modStylesFile = new File(tmpDir, "_styles.scss");
        FileUtils.writeStringToFile(modStylesFile, 
                IOUtils.toString(SassCompilerTest.class.getResourceAsStream("sass01/_styles.scss")).replace("blue", "green"));
        Contentlet asset = APILocator.getContentletAPI().search(
                "+conhost:"+demo.getIdentifier()+" +confolder:"+folder.getInode()+" +fileasset.filename:_styles.scss", 
                1, 0, "", sysuser, false).get(0);
        asset = APILocator.getContentletAPI().checkout(asset.getInode(), sysuser, false);
        asset.setBinary(FileAssetAPI.BINARY_FIELD, modStylesFile);
        asset = APILocator.getContentletAPI().checkin(asset, sysuser, false);
        APILocator.getContentletAPI().publish(asset, sysuser, false);
        APILocator.getContentletAPI().isInodeIndexed(asset.getInode(),true);
        
        Assert.assertEquals(expectedOutput.replace("blue", "green").trim(), IOUtils.toString(cssURL.openStream(),"UTF-8").trim());
        
        
        // check every asset is in cache
        CachedCSS cc = CacheLocator.getCSSCache().get(demo.getIdentifier(), folder.getPath()+"_layout.scss", true);
        Assert.assertNotNull(cc);
        cc = CacheLocator.getCSSCache().get(demo.getIdentifier(), folder.getPath()+"_styles.scss", true);
        Assert.assertNotNull(cc);
        cc = CacheLocator.getCSSCache().get(demo.getIdentifier(), folder.getPath()+"screen.scss", true);
        Assert.assertNotNull(cc);
        
        // if we unpublish _styles.scss we expect the cache entry to be removed
        APILocator.getContentletAPI().unpublish(asset, sysuser, false);
        cc = CacheLocator.getCSSCache().get(demo.getIdentifier(), folder.getPath()+"_styles.scss", true);
        Assert.assertNull(cc);
        
        // now with an archive. it should be wiped too
        asset = APILocator.getContentletAPI().search(
                "+conhost:"+demo.getIdentifier()+" +confolder:"+folder.getInode()+" +fileasset.filename:_layout.scss", 
                1, 0, "", sysuser, false).get(0);
        APILocator.getContentletAPI().archive(asset, sysuser, false);
        cc = CacheLocator.getCSSCache().get(demo.getIdentifier(), folder.getPath()+"_layout.scss", true);
        Assert.assertNull(cc);
    }
}
