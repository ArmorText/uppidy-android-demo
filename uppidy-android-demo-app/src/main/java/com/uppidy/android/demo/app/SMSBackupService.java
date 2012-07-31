package com.uppidy.android.demo.app;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.social.connect.ConnectionRepository;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.PhoneLookup;

import com.uppidy.android.sdk.backup.BackupService;
import com.uppidy.android.sdk.backup.MessageProvider;
import com.uppidy.android.sdk.social.api.Contact;
import com.uppidy.android.sdk.social.api.ContactInfo;
import com.uppidy.android.sdk.social.api.Message;
import com.uppidy.android.sdk.social.api.Uppidy;

public class SMSBackupService extends BackupService
{	
	private static final String   ACTION_SMS_BACKUP = "com.uppidy.android.demo.SMS_BACKUP";
	public  static final String[] CONTACTS_QUERY_PROJECTION = new String[] { 
		ContactsContract.PhoneLookup.NUMBER,
		ContactsContract.PhoneLookup.DISPLAY_NAME 
	};
	public  static final String[] SMS_QUERY_PROJECTION = new String[] { 
		"_id", "address", "body", "date", "type" 
		};
	public  static final String   SMS_WHERE_CLAUSE = "date > ? and date < ? and type IN(1,2)";
	public  static final String   SMS_ORDER = "date asc";
	public  static final String   SMS_URI = "content://sms/";
	public  static final long     SMS_TIME_GAP = 2000L; // 2 minutes
	public  static final int      SMS_TYPE_IN  = 1;
	public  static final int      SMS_TYPE_SENT = 2;
	private static final int      MAX_SMS = 10;
	
	public SMSBackupService()
	{
		super( "SMS Backup" );
	}

	@Override
	public void onCreate()
	{
		super.onCreate();
		super.addMessageProvider( ACTION_SMS_BACKUP, new SMSMessageProvider( (MainApplication)getApplicationContext()) );		
	}
	
	@Override
	protected ConnectionRepository getUppidyConnectionRepository()
	{
		return ((MainApplication)getApplicationContext()).getConnectionRepository();
	}
	
	@Override
	protected SharedPreferences getSharedPreferences()
	{
		return ((MainApplication)getApplicationContext()).getSettings();
	}
	
	// ***************************************
	// Private classes
	// ***************************************
	class SMSMessageProvider implements MessageProvider
	{
		MainApplication context;
		Date            firstSyncDate  = null;
		Date            lastSyncDate   = null;
		boolean         needReloadDates = true;
		
		public SMSMessageProvider( MainApplication context )
		{
			this.context = context;
		}
		
		@Override
		public void backupDone( List<Message> messages )
		{
			//if this backup batch was successful - no need to reload the dates on the next turn
			needReloadDates = false;
			for( Message m : messages )
			{
				if( firstSyncDate == null || firstSyncDate.after(m.getSentTime()) ) 
				{
					firstSyncDate = m.getSentTime();
				}
				if( lastSyncDate == null || lastSyncDate.before(m.getSentTime()) ) 
				{
					lastSyncDate = m.getSentTime();
				}
			}
		}

		@Override
		public List<Contact> getContacts( List<String> contactIds )
		{
			List<Contact> contacts = new ArrayList<Contact>();
			for( String id : contactIds ) contacts.add( getContactFromNumber(id) );
			return contacts;
		}

		@Override
		public String getContainerId()
		{
			return context.getContainerId();
		}

		@Override
		public List<Message> getNextSyncBundle()
		{
			if( needReloadDates ) 
			{
				Uppidy uppidy = context.getConnectionRepository().findPrimaryConnection(Uppidy.class).getApi();
				firstSyncDate = uppidy.backupOperations().getFirstMessageSyncDate( getContainerId() );
				lastSyncDate  = uppidy.backupOperations().getLastMessageSyncDate( getContainerId() );
			}
			List<Message> messages = getMessages( lastSyncDate, MAX_SMS );
			// if we have some SMS for backup, let's assume this backup session is going to fail 
			// until otherwise said explicitly by BackupService via backupDone method call.
			// If it fails - we have to reload the dates because some of the messages may be backed up
			// even thought the backup was failed
			if( messages.size() > 0 ) needReloadDates = true;
			return messages;
		}
		
		private List<Message> getMessages( Date from, int num )
		{
			List<Message> messages = new ArrayList<Message>();
			
			Cursor cursor = null;
			if( from == null )
			{
				cursor = getContentResolver().query( Uri.parse(SMS_URI), SMS_QUERY_PROJECTION, null, null, SMS_ORDER );
			}
			else 
			{
				String[] where = new String[] { String.valueOf(from.getTime()),
						String.valueOf( Calendar.getInstance().getTimeInMillis() - SMS_TIME_GAP) }; 
				cursor = getContentResolver().query( Uri.parse(SMS_URI), SMS_QUERY_PROJECTION, SMS_WHERE_CLAUSE, 
						where, SMS_ORDER );
			}
			
			cursor.moveToFirst();
			int size = cursor.getCount();
			ContactInfo me = context.getContainer().getOwner();
			for( int i = 0; i < num && i < size; i++, cursor.moveToNext() )
			{
				String mID = cursor.getString(cursor.getColumnIndexOrThrow("_id"));
				String smsAddress = cursor.getString(cursor.getColumnIndexOrThrow("address"));
				String smsBody = cursor.getString(cursor.getColumnIndexOrThrow("body"));
				Long smsDate = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
				int type = cursor.getInt(cursor.getColumnIndexOrThrow("type"));
				Message m = new Message();
				m.setId( mID );
				ContactInfo other = new ContactInfo();
				other.setAddress(smsAddress);
				switch( type )
				{
					case SMS_TYPE_IN: 
						m.setFrom( other );
						m.setTo(Collections.singletonList( me ) );
						m.setSent( false );
						break;
					//case SMS_TYPE_SENT:
					default:
						m.setFrom(me);
						m.setTo( Collections.singletonList( other ) ); 
						m.setSent( true );
						break;
				}
				m.setSentTime( new Date(smsDate) );
				m.setText(smsBody);
				messages.add( m );
			}
			
			return messages;
		}
		
		private Contact getContactFromNumber(String phoneNumber) 
		{
			if (phoneNumber == null ) return null; 
			Contact contact = new Contact();
			// temp variables to hold the contact information
			String name = phoneNumber;
			String number = phoneNumber;
			if( phoneNumber.length() != 0 ) 
			{
				Uri lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));

				Cursor contactsCursor = getContentResolver().query(lookupUri, CONTACTS_QUERY_PROJECTION,
																				null, null, null );
				if (contactsCursor.moveToFirst()) 
				{
					// we create a contact with the number and name as they are stored by the user
					name = contactsCursor.getString(contactsCursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME));
					number = contactsCursor.getString(contactsCursor.getColumnIndexOrThrow(PhoneLookup.NUMBER));
				} 
				
				contactsCursor.close();
			} 
			else // no phone number provided 
			{
				name = "Unknown";
				number = "Unknown";
			}
			contact.setName( name );
			Map<String, List<String>> addressByType = new HashMap<String, List<String>>();
			addressByType.put("phone", Collections.singletonList(number));
			contact.setAddressByType(addressByType);
			return contact;
		}
	}
}
