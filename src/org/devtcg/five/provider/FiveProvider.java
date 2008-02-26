/*
 * $Id$
 *
 * Copyright (C) 2008 Josh Guilfoyle <jasta@devtcg.org>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package org.devtcg.five.provider;

import java.util.HashMap;
import java.util.List;

import android.content.ContentProvider;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentUris;
import android.content.UriMatcher;
import android.content.ContentValues;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

public class FiveProvider extends ContentProvider
{
	private static final String TAG = "FiveProvider";

	private SQLiteDatabase mDB;
	private static final String DATABASE_NAME = "five.db";
	private static final int DATABASE_VERSION = 13;

	private static final UriMatcher URI_MATCHER;
	private static final HashMap<String, String> sourcesMap;
	private static final HashMap<String, String> artistsMap;
	private static final HashMap<String, String> albumsMap;

	private static enum URIPatternIds
	{
		SOURCES, SOURCE, SOURCE_LOG,
		ARTISTS, ARTIST,
		ALBUMS, ALBUMS_BY_ARTIST, ALBUM,
		SONGS, SONGS_BY_ALBUM, SONGS_BY_ARTIST, SONG,
		CONTENT, CONTENT_ITEM
		;

		public static URIPatternIds get(int ordinal)
		{
			return values()[ordinal];
		}
	}

	private static class DatabaseHelper extends SQLiteOpenHelper
	{
		@Override
		public void onCreate(SQLiteDatabase db)
		{
			db.execSQL(Five.Sources.SQL.CREATE);
			db.execSQL(Five.Sources.SQL.INSERT_DUMMY);
			db.execSQL(Five.SourcesLog.SQL.CREATE);

			db.execSQL(Five.Content.SQL.CREATE);
			db.execSQL(Five.Music.Artists.SQL.CREATE);
			db.execSQL(Five.Music.Albums.SQL.CREATE);
			db.execSQL(Five.Music.Songs.SQL.CREATE);
		}

		private void onDrop(SQLiteDatabase db)
		{
			db.execSQL(Five.Sources.SQL.DROP);
			db.execSQL(Five.SourcesLog.SQL.DROP);
			
			db.execSQL(Five.Content.SQL.DROP);
			db.execSQL(Five.Music.Artists.SQL.DROP);
			db.execSQL(Five.Music.Albums.SQL.DROP);
			db.execSQL(Five.Music.Songs.SQL.DROP);
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
		{
			Log.w(TAG, "Version too old, wiping out database contents...");
			onDrop(db);
			onCreate(db);
		}
	}

	@Override
	public boolean onCreate()
	{
		DatabaseHelper dbh = new DatabaseHelper();
		mDB = dbh.openDatabase(getContext(), DATABASE_NAME, null, DATABASE_VERSION);

		return (mDB == null) ? false : true;
	}
	
	private static String getSecondToLastPathSegment(Uri uri)
	{
		List<String> segments = uri.getPathSegments();
		int size;
		
		if ((size = segments.size()) < 2)
			throw new IllegalArgumentException("URI is not long enough to have a second-to-last path");
		
		return segments.get(size - 2);
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder)
	{
		SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
		String groupBy = null;

		switch (URIPatternIds.get(URI_MATCHER.match(uri)))
		{
		case SOURCES:
			qb.setTables(Five.Sources.SQL.TABLE + " s " +
			  "LEFT JOIN " + Five.SourcesLog.SQL.TABLE + " sl " +
			  "ON sl.source_id = s._id " +
			  "AND sl.type = " + Five.SourcesLog.TYPE_ERROR + " " +
			  "AND sl.timestamp > s.revision");
			qb.setProjectionMap(sourcesMap);
			groupBy = "s._id";
			break;

		case SOURCE:
			qb.setTables(Five.Sources.SQL.TABLE);
			qb.appendWhere("_id=" + uri.getLastPathSegment());
			break;
			
		case SOURCE_LOG:
			qb.setTables(Five.SourcesLog.SQL.TABLE);
			qb.appendWhere("source_id=" + uri.getPathSegments().get(1));
			break;

		case SONGS:
			qb.setTables(Five.Music.Songs.SQL.TABLE);
			break;

		case SONG:
			qb.setTables(Five.Music.Songs.SQL.TABLE);
			qb.appendWhere("_id=" + uri.getLastPathSegment());
			break;

		case ARTISTS:
			qb.setTables(Five.Music.Artists.SQL.TABLE);
			qb.setProjectionMap(artistsMap);
			break;

		case ARTIST:
			qb.setTables(Five.Music.Artists.SQL.TABLE);
			qb.appendWhere("_id=" + uri.getLastPathSegment());
			qb.setProjectionMap(artistsMap);
			break;
			
		case ALBUMS:
			qb.setTables(Five.Music.Albums.SQL.TABLE);
			qb.setProjectionMap(albumsMap);
			break;

		case ALBUM:
			qb.setTables(Five.Music.Albums.SQL.TABLE);
			qb.appendWhere("_id=" + uri.getLastPathSegment());
			qb.setProjectionMap(albumsMap);
			break;
			
		case ALBUMS_BY_ARTIST:
			qb.setTables(Five.Music.Albums.SQL.TABLE);
			qb.appendWhere("artist_id=" + getSecondToLastPathSegment(uri));
			qb.setProjectionMap(albumsMap);
			break;

		default:
			throw new IllegalArgumentException("Unknown URI: " + uri);
		}

		Cursor c = qb.query(mDB, projection, selection, selectionArgs, groupBy, null, sortOrder);

		c.setNotificationUri(getContext().getContentResolver(), uri);

		return c;
	}

	private int updateSource(Uri uri, URIPatternIds type, ContentValues v,
	  String sel, String[] selArgs)
	{
		String id = "_id=" + uri.getLastPathSegment();

		if (sel == null)
			sel = id;
		else
			sel = id + " AND (" + sel + ")";

		int ret = mDB.update(Five.Sources.SQL.TABLE, v, sel, selArgs);
		getContext().getContentResolver().notifyChange(uri, null);
		
		return ret;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection,
	  String[] selectionArgs)
	{		
		URIPatternIds type = URIPatternIds.get(URI_MATCHER.match(uri));
		
		switch (type)
		{
		case SOURCE:
			return updateSource(uri, type, values, selection, selectionArgs);
		default:
			throw new IllegalArgumentException("Cannot insert URI: " + uri);
		}
	}
	
	private Uri insertSource(Uri uri, URIPatternIds type, ContentValues v)
	{
		return null;
	}
	
	private Uri insertSourceLog(Uri uri, URIPatternIds type, ContentValues v)
	{
		String sourceId = uri.getPathSegments().get(1);  

		if (v.containsKey(Five.SourcesLog.SOURCE_ID) == true)
			throw new IllegalArgumentException("SOURCE_ID must be provided through the URI, not the columns");
		
		v.put(Five.SourcesLog.SOURCE_ID, sourceId);

		if (v.containsKey(Five.SourcesLog.TIMESTAMP) == false)
			v.put(Five.SourcesLog.TIMESTAMP, System.currentTimeMillis() / 1000);

		long id = mDB.insert(Five.SourcesLog.SQL.TABLE, Five.SourcesLog.SOURCE_ID, v);

		if (id == -1)
			return null;

		Uri ret = Five.Sources.CONTENT_URI.buildUpon()
		  .appendPath(sourceId)
		  .appendPath("log")
		  .appendPath(String.valueOf(id))
		  .build();

		getContext().getContentResolver().notifyChange(ret, null);

		return ret;
	}
	
	private Uri insertContent(Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Content.SOURCE_ID) == false)
			throw new IllegalArgumentException("SOURCE_ID cannot be NULL");
		
		long id = mDB.insert(Five.Content.SQL.TABLE, Five.Content.SIZE, v);
		
		if (id == -1)
			return null;
		
		Uri ret = ContentUris.withAppendedId(Five.Content.CONTENT_URI, id);
		getContext().getContentResolver().notifyChange(ret, null);
		
		return ret;
	}
	
	private boolean adjustNameWithPrefix(ContentValues v)
	{
		String name = v.getAsString(Five.Music.Artists.NAME);

		if (name.startsWith("The ") == true)
		{
			v.put(Five.Music.Artists.NAME, name.substring(4));
			v.put(Five.Music.Artists.NAME_PREFIX, "The ");
			
			return true;
		}
		
		return false;
	}

	private Uri insertArtist(Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Music.Artists.NAME) == false)
			throw new IllegalArgumentException("NAME cannot be NULL");
		
		adjustNameWithPrefix(v);

		long id = mDB.insert(Five.Music.Artists.SQL.TABLE, Five.Music.Artists.NAME, v);

		if (id == -1)
			return null;

		Uri ret = ContentUris.withAppendedId(Five.Music.Artists.CONTENT_URI, id);
		getContext().getContentResolver().notifyChange(ret, null);
		return ret;
	}

	private Uri insertAlbum(Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Music.Albums.NAME) == false)
			throw new IllegalArgumentException("NAME cannot be NULL");

		if (v.containsKey(Five.Music.Albums.ARTIST_ID) == false)
			throw new IllegalArgumentException("ARTIST_ID cannot be NULL");

		adjustNameWithPrefix(v);

		long id = mDB.insert(Five.Music.Albums.SQL.TABLE, Five.Music.Albums.NAME, v);

		if (id == -1)
			return null;

		Uri ret = ContentUris.withAppendedId(Five.Music.Albums.CONTENT_URI, id);
		getContext().getContentResolver().notifyChange(ret, null);

		long artistId = v.getAsLong(Five.Music.Albums.ARTIST_ID);
		Uri artistUri = ContentUris.withAppendedId(Five.Music.Artists.CONTENT_URI, artistId);

		getContext().getContentResolver().notifyChange(artistUri, null);

		return ret;
	}

	private Uri insertSong(Uri uri, URIPatternIds type, ContentValues v)
	{
		if (v.containsKey(Five.Music.Songs.ARTIST_ID) == false)
			throw new IllegalArgumentException("ARTIST_ID cannot be NULL");

		if (v.containsKey(Five.Music.Songs.CONTENT_ID) == false)
			throw new IllegalArgumentException("CONTENT_ID cannot be NULL");

		long id = mDB.insert(Five.Music.Songs.SQL.TABLE, Five.Music.Songs.TITLE, v);

		if (id == -1)
			return null;

		Uri ret = ContentUris.withAppendedId(Five.Music.Songs.CONTENT_URI, id);
		getContext().getContentResolver().notifyChange(ret, null);

		long artistId = v.getAsLong(Five.Music.Songs.ARTIST_ID);
		Uri artistUri = ContentUris.withAppendedId(Five.Music.Artists.CONTENT_URI, artistId);

		getContext().getContentResolver().notifyChange(artistUri, null);

		if (v.containsKey(Five.Music.Songs.ALBUM_ID) == true)
		{
			long albumId = v.getAsLong(Five.Music.Songs.ALBUM_ID);
			Uri albumUri = ContentUris.withAppendedId(Five.Music.Albums.CONTENT_URI, albumId);

			getContext().getContentResolver().notifyChange(albumUri, null);
		}

		long contentId = v.getAsLong(Five.Music.Songs.CONTENT_ID);
		Uri Uri = ContentUris.withAppendedId(Five.Music.Songs.CONTENT_URI, contentId);

		getContext().getContentResolver().notifyChange(Uri, null);
		
		return ret;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values)
	{
		URIPatternIds type = URIPatternIds.get(URI_MATCHER.match(uri));

		switch (type)
		{
		case SOURCES:
			return insertSource(uri, type, values);
		case SOURCE_LOG:
			return insertSourceLog(uri, type, values);
		case CONTENT_ITEM:
			return insertContent(uri, type, values);
		case ARTISTS:
			return insertArtist(uri, type, values);
		case ALBUMS:
			return insertAlbum(uri, type, values);
		case SONGS:
			return insertSong(uri, type, values);
		default:
			throw new IllegalArgumentException("Cannot insert URI: " + uri);
		}
	}

	private int deleteSource(Uri uri, URIPatternIds type, 
	  String selection, String[] selectionArgs)
	{
		int count;

		count = mDB.delete(Five.Sources.SQL.TABLE, selection, selectionArgs);
		getContext().getContentResolver().notifyChange(uri, null);

		return count;
	}

	private int deleteContent(Uri uri, URIPatternIds type, 
	  String selection, String[] selectionArgs)
	{
		String custom;
		int count;
		
		switch (type)
		{
		case CONTENT:
			custom = selection;
			break;

		case CONTENT_ITEM:
			StringBuilder where = new StringBuilder();
			where.append(Five.Content._ID).append('=').append(uri.getLastPathSegment());
			
			if (TextUtils.isEmpty(selection) == false)
				where.append(" AND (").append(selection).append(')');

			custom = where.toString();			
			break;

		default:
			throw new IllegalArgumentException("Cannot delete content URI: " + uri);
		}

		count = mDB.delete(Five.Content.SQL.TABLE, custom, selectionArgs);
		getContext().getContentResolver().notifyChange(uri, null);

		return count;
	}
	
	private int deleteArtist(Uri uri, URIPatternIds type, 
	  String selection, String[] selectionArgs)
	{
		String custom;
		int count;

		switch (type)
		{
		case ARTISTS:
			custom = selection;
			break;

		case ARTIST:
			StringBuilder where = new StringBuilder();
			where.append(Five.Music.Artists._ID).append('=').append(uri.getLastPathSegment());
			
			if (TextUtils.isEmpty(selection) == false)
				where.append(" AND (").append(selection).append(')');

			custom = where.toString();			
			break;

		default:
			throw new IllegalArgumentException("Cannot delete artist URI: " + uri);
		}

		count = mDB.delete(Five.Music.Artists.SQL.TABLE, custom, selectionArgs);
		getContext().getContentResolver().notifyChange(uri, null);

		return count;
	}

	private int deleteAlbum(Uri uri, URIPatternIds type, 
	  String selection, String[] selectionArgs)
	{
		String custom;
		int count;
		
		switch (type)
		{
		case ALBUMS:
			custom = selection;
			break;
			
		case ALBUM:
			StringBuilder where = new StringBuilder();
			where.append(Five.Music.Albums._ID).append('=').append(uri.getLastPathSegment());
			
			if (TextUtils.isEmpty(selection) == false)
				where.append(" AND (").append(selection).append(')');
			
			custom = where.toString();
			break;
			
		default:
			throw new IllegalArgumentException("Cannot delete album URI: " + uri);
		}
		
		count = mDB.delete(Five.Music.Albums.SQL.TABLE, custom, selectionArgs);
		getContext().getContentResolver().notifyChange(uri, null);

		return count;
	}

	private int deleteSong(Uri uri, URIPatternIds type, 
	  String selection, String[] selectionArgs)
	{
		String custom;
		int count;
		
		switch (type)
		{
		case SONGS:
			custom = selection;
			break;
		
		case SONG:
			StringBuilder where = new StringBuilder();
			where.append(Five.Music.Songs._ID).append('=').append(uri.getLastPathSegment());
			
			if (TextUtils.isEmpty(selection) == false)
				where.append(" AND (").append(selection).append(')');
			
			custom = where.toString();	
			break;

		default:
			throw new IllegalArgumentException("Cannot delete song URI: " + uri);
		}

		count = mDB.delete(Five.Music.Songs.SQL.TABLE, custom, selectionArgs);
		getContext().getContentResolver().notifyChange(uri, null);

		return count;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs)
	{
		URIPatternIds type = URIPatternIds.get(URI_MATCHER.match(uri));

		switch (type)
		{
		case SOURCES:
			return deleteSource(uri, type, selection, selectionArgs);
		case CONTENT:
		case CONTENT_ITEM:
			return deleteContent(uri, type, selection, selectionArgs);
		case ARTISTS:
		case ARTIST:
			return deleteArtist(uri, type, selection, selectionArgs);
		case ALBUMS:
		case ALBUM:
			return deleteAlbum(uri, type, selection, selectionArgs);
		case SONGS:
		case SONG:
			return deleteSong(uri, type, selection, selectionArgs);
		default:
			throw new IllegalArgumentException("Cannot delete URI: " + uri);
		}
	}

	@Override
	public String getType(Uri uri)
	{
		switch (URIPatternIds.get(URI_MATCHER.match(uri)))
		{
		case SOURCES:
			return Five.Sources.CONTENT_TYPE;
		case SOURCE:
			return Five.Sources.CONTENT_ITEM_TYPE;
		case ARTISTS:
			return Five.Music.Artists.CONTENT_TYPE;
		case ARTIST:
			return Five.Music.Artists.CONTENT_ITEM_TYPE;
		case ALBUMS:
		case ALBUMS_BY_ARTIST:
			return Five.Music.Albums.CONTENT_TYPE;
		case ALBUM:
			return Five.Music.Albums.CONTENT_ITEM_TYPE;
		case SONGS:
		case SONGS_BY_ALBUM:
		case SONGS_BY_ARTIST:
			return Five.Music.Songs.CONTENT_TYPE;
		case SONG:
			return Five.Music.Songs.CONTENT_ITEM_TYPE;
		default:
			throw new IllegalArgumentException("Unknown URI: " + uri);
		}
	}
	
	static
	{
		URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
		URI_MATCHER.addURI(Five.AUTHORITY, "sources", URIPatternIds.SOURCES.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "sources/#", URIPatternIds.SOURCE.ordinal());

		URI_MATCHER.addURI(Five.AUTHORITY, "sources/#/log", URIPatternIds.SOURCE_LOG.ordinal());

		URI_MATCHER.addURI(Five.AUTHORITY, "content", URIPatternIds.CONTENT.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "content/#", URIPatternIds.CONTENT_ITEM.ordinal());

		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/artists", URIPatternIds.ARTISTS.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/artists/#", URIPatternIds.ARTIST.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/artists/#/albums", URIPatternIds.ALBUMS_BY_ARTIST.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/artists/#/songs", URIPatternIds.SONGS_BY_ARTIST.ordinal());
		
		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/albums", URIPatternIds.ALBUMS.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/albums/#", URIPatternIds.ALBUM.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/albums/#/songs", URIPatternIds.SONGS_BY_ALBUM.ordinal());

		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/songs", URIPatternIds.SONGS.ordinal());
		URI_MATCHER.addURI(Five.AUTHORITY, "media/music/songs/#", URIPatternIds.SONG.ordinal());
		
		sourcesMap = new HashMap<String, String>();
		sourcesMap.put(Five.Sources._ID, "s." + Five.Sources._ID + " AS " + Five.Sources._ID);
		sourcesMap.put(Five.Sources.HOST, "s." + Five.Sources.HOST + " AS " + Five.Sources.HOST);
		sourcesMap.put(Five.Sources.NAME, "s." + Five.Sources.NAME + " AS " + Five.Sources.NAME);
		sourcesMap.put(Five.Sources.PORT, "s." + Five.Sources.PORT + " AS " + Five.Sources.PORT);
		sourcesMap.put(Five.Sources.REVISION, "s." + Five.Sources.REVISION + " AS " + Five.Sources.REVISION);
		sourcesMap.put(Five.Sources.LAST_ERROR, "sl." + Five.SourcesLog.MESSAGE + " AS " + Five.Sources.LAST_ERROR);
		
		artistsMap = new HashMap<String, String>();
		artistsMap.put(Five.Music.Artists._ID, Five.Music.Artists._ID);
		artistsMap.put(Five.Music.Artists.DISCOVERY_DATE, Five.Music.Artists.DISCOVERY_DATE);
		artistsMap.put(Five.Music.Artists.GENRE, Five.Music.Artists.GENRE);
		artistsMap.put(Five.Music.Artists.NAME, Five.Music.Artists.NAME);
		artistsMap.put(Five.Music.Artists.NAME_PREFIX, Five.Music.Artists.NAME_PREFIX);
		artistsMap.put(Five.Music.Artists.FULL_NAME, "IFNULL(" + Five.Music.Artists.NAME_PREFIX + ", \"\") || " + Five.Music.Artists.NAME + " AS " + Five.Music.Artists.FULL_NAME);
		artistsMap.put(Five.Music.Artists.PHOTO_ID, Five.Music.Artists.PHOTO_ID);
		
		albumsMap = new HashMap<String, String>();
		albumsMap.put(Five.Music.Albums._ID, Five.Music.Albums._ID);
		albumsMap.put(Five.Music.Albums.ARTIST_ID, Five.Music.Albums.ARTIST_ID);
		albumsMap.put(Five.Music.Albums.ARTWORK_ID, Five.Music.Albums.ARTWORK_ID);
		albumsMap.put(Five.Music.Albums.DISCOVERY_DATE, Five.Music.Albums.DISCOVERY_DATE);
		albumsMap.put(Five.Music.Albums.NAME, Five.Music.Albums.NAME);
		albumsMap.put(Five.Music.Albums.NAME_PREFIX, Five.Music.Albums.NAME_PREFIX);
		albumsMap.put(Five.Music.Albums.FULL_NAME, "IFNULL(" + Five.Music.Albums.NAME_PREFIX + ", \"\") || " + Five.Music.Albums.NAME + " AS " + Five.Music.Albums.FULL_NAME);
		albumsMap.put(Five.Music.Albums.RELEASE_DATE, Five.Music.Albums.RELEASE_DATE);
	}
}
