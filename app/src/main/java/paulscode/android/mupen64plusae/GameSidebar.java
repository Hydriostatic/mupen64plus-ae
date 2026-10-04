/**
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * Copyright (C) 2013 Paul Lamb
 * 
 * This file is part of Mupen64PlusAE.
 * 
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 * 
 * Authors: BonzaiThePenguin
 */
package paulscode.android.mupen64plusae;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

public class GameSidebar extends MenuListView
{
    private ImageView mInfoArt;
    private TextView mGameTitle;
    private GameSidebarActionHandler mActionHandler;
    private View mHeader;
    private boolean mHeaderShown = true;
    private boolean mHeaderIsLogo = false;
    private Runnable mHeaderChangedListener;

    public GameSidebar( Context context, AttributeSet attrs) {
        super( context, attrs );

        LayoutInflater inflater = (LayoutInflater) context.getSystemService( Context.LAYOUT_INFLATER_SERVICE );
        mHeader = inflater.inflate( R.layout.game_sidebar_header, this, false );

        mInfoArt = mHeader.findViewById( R.id.imageArt );
        mGameTitle = mHeader.findViewById( R.id.gameTitle );

        setClipToPadding(true);
        addHeaderView(mHeader, null, false);
    }
    
    public void setActionHandler(GameSidebarActionHandler actionHandler, int menuResource)
    {
        mActionHandler = actionHandler;
        setMenuResource( menuResource );
        
        setNextFocusDownId(getId());
        setNextFocusLeftId(getId());
        setNextFocusRightId(getId());
        setNextFocusUpId(getId());
        
        // Handle menu item selections
        setOnClickListener((OnClickListener) menuItem -> mActionHandler.onGameSidebarAction( menuItem ));

        setOnKeyListener(actionHandler);
    }
    
    public void setImage( BitmapDrawable image )
    {
        if( image != null ) {
            mInfoArt.setImageDrawable(image);
        }
        else
            mInfoArt.setImageResource( R.drawable.default_coverart );
        mHeaderIsLogo = false;
        notifyHeaderChanged();
    }

    /** An app logo (rather than a game's cover art) as the header image. */
    public void setImage(int resourceId) {
        mInfoArt.setImageResource(resourceId);
        mHeaderIsLogo = true;
        notifyHeaderChanged();
    }

    /**
     * Show or hide the header (image and title) at the top of the list. The second screen hides
     * it and shows the image in a fixed band above the list instead.
     */
    public void setHeaderShown( boolean shown )
    {
        if( shown == mHeaderShown ) return;
        mHeaderShown = shown;
        if( shown )
            addHeaderView( mHeader, null, false );
        else
            removeHeaderView( mHeader );
    }

    /** The header image (cover art, or the app logo). */
    public android.graphics.drawable.Drawable getHeaderImage()
    {
        return mInfoArt.getDrawable();
    }

    /** True if the header image is the app logo, false if it is a game's cover art. */
    public boolean isHeaderImageLogo()
    {
        return mHeaderIsLogo;
    }

    /** Called whenever the header image changes. */
    public void setOnHeaderChangedListener( Runnable listener )
    {
        mHeaderChangedListener = listener;
    }

    private void notifyHeaderChanged()
    {
        if( mHeaderChangedListener != null ) mHeaderChangedListener.run();
    }
    
    public void setTitle( String title )
    {
        mGameTitle.setText( title );
    }

    public void hideTitle() {
        mGameTitle.setVisibility(View.INVISIBLE);
        mGameTitle.setHeight(0);
        mGameTitle.setPadding(0,0,0,0);
    }
    
    public interface GameSidebarActionHandler extends OnKeyListener
    {
        void onGameSidebarAction(MenuItem menuItem);
    }
}
