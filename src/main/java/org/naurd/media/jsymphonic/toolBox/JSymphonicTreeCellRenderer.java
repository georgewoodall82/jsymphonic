/*
 * Copyright (C) 2007, 2008, 2009 Patrick Balleux, Nicolas Cardoso De Castro
 * (nicolas_cardoso@users.sourceforge.net), Daniel Žalar (danizmax@gmail.com)
 *
 * This file is part of JSymphonic program.
 *
 * JSymphonic is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JSymphonic is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JSymphonic. If not, see <http://www.gnu.org/licenses/>.
 *
 *****
 *
 * MyTreeRenderer.java
 *
 * Created on 20 juillet 2007, 18:36
 *
 */

package org.naurd.media.jsymphonic.toolBox;

import java.awt.Color;
import java.awt.Component;
import javax.swing.ImageIcon;
import javax.swing.JTree;
import javax.swing.UIManager;
import org.danizmax.jsymphonic.toolkit.JSymphonicMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.naurd.media.jsymphonic.title.Title;

import com.pipasoft.jsymphonic.ResourceLoader;

/**
 *
 * @author skiron
 * @author danizmax - Daniel Žalar (danizmax@gmail.com) - add forground colors
 */
public class JSymphonicTreeCellRenderer extends DefaultTreeCellRenderer {
    
    void doColorIcons(){
    }
    
    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);


        if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_USB ) { 
           setIcon(ResourceLoader.getIcon("device.png"));
        }
        else if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_ALBUM ) { 
           setIcon(ResourceLoader.getIcon("album.png"));
        }
        else if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_ARTIST ) {
           setIcon(ResourceLoader.getIcon("artist.png"));
        }
        else if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_GENRE ) {
           setIcon(ResourceLoader.getIcon("genre.png"));
        }
        else if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_PLAYLIST ) {
           setIcon(ResourceLoader.getIcon("playlist.png"));
        }
        else if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_TITLE ) {
           setIcon(ResourceLoader.getIcon("title.png"));
        }
        else if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_FOLDER ) {
           setIcon(ResourceLoader.getIcon("folder.png"));
        }
        else if( ((JSymphonicMutableTreeNode)value).getType() == JSymphonicMutableTreeNode.TYPE_AUDIOFILE ) {
           setIcon(ResourceLoader.getIcon("audiofile.png"));
        }
        else {
           setIcon(ResourceLoader.getIcon("default.png"));
        }
        
        //set the action color
        if( ((JSymphonicMutableTreeNode)value).getNodeAction() == Title.TO_DELETE) {
            this.setForeground(Color.RED);
        }else if( ((JSymphonicMutableTreeNode)value).getNodeAction() ==Title.TO_EXPORT ){
            this.setForeground(Color.BLUE);
        }else if( (((JSymphonicMutableTreeNode)value).getNodeAction() == Title.TO_IMPORT ) || ( ((JSymphonicMutableTreeNode)value).getNodeAction() ==Title.TO_ENCODE )  || ( ((JSymphonicMutableTreeNode)value).getNodeAction() ==Title.TO_DECODE ) ){
            this.setForeground(Color.GREEN);
        }else if( ((JSymphonicMutableTreeNode)value).getNodeAction() == Title.ON_DEVICE ) {
            this.setForeground(UIManager.getColor(Color.BLACK));
        }

        return this;
    }
}
