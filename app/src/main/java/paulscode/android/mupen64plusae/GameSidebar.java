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
import android.graphics.Rect;
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
    }

    public void setImage(int resourceId) {
        mInfoArt.setImageResource(resourceId);
    }
    
    public void setTitle( String title )
    {
        mGameTitle.setText( title );
    }

    /** Compact mode (second screen): hide the big cover art, keep the title. */
    public void setCompact(boolean compact)
    {
        View art = mHeader.findViewById(R.id.imageLayout);
        if (art != null) art.setVisibility(compact ? View.GONE : View.VISIBLE);
    }

    // A view shown instead of this list (e.g. the button home on the second screen). Code that
    // shows/hides/focuses this sidebar then shows/hides/focuses the replacement instead.
    private View mReplacement;

    public void setReplacementView(View replacement)
    {
        int visibility = getVisibility();
        if (mReplacement != null) super.setVisibility(mReplacement.getVisibility());
        mReplacement = replacement;
        if (replacement != null) {
            replacement.setVisibility(visibility);
            super.setVisibility(View.GONE);
        }
    }

    @Override
    public void setVisibility(int visibility)
    {
        if (mReplacement != null) {
            mReplacement.setVisibility(visibility);
            super.setVisibility(View.GONE);
        } else {
            super.setVisibility(visibility);
        }
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect)
    {
        if (mReplacement != null) return mReplacement.requestFocus(direction, previouslyFocusedRect);
        return super.requestFocus(direction, previouslyFocusedRect);
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
