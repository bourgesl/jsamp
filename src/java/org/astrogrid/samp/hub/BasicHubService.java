package org.astrogrid.samp.hub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.astrogrid.samp.Message;
import org.astrogrid.samp.RegInfo;
import org.astrogrid.samp.Response;
import org.astrogrid.samp.SampException;
import org.astrogrid.samp.SampUtils;

public class BasicHubService implements HubService {

    private final String password_;
    private final Random random_;
    private final ClientSet clientSet_;
    private final Client hubClient_;
    private final KeyGenerator keyGen_;
    private final SortedMap waiterMap_;
    private static final char ID_DELIMITER = '_';
    private int clientCount_;
    private boolean shutdown_;
    private final Logger logger_ =
        Logger.getLogger( BasicHubService.class.getName() );

    public static final int MAX_TIMEOUT = 12 * 60 * 60;
    public static final int MAX_WAITERS = 100;
 
    public BasicHubService() {
        random_ = new Random( System.currentTimeMillis() );
        password_ = Long.toHexString( random_.nextLong() );
        keyGen_ = new KeyGenerator( "k:", 16, random_ );
        waiterMap_ =
            Collections
           .synchronizedSortedMap( new TreeMap( MessageId.AGE_COMPARATOR ) );
        clientSet_ = new ClientSet();
        hubClient_ = new Client( keyGen_.next(), "hub" );
        clientSet_.add( hubClient_ );
        Runtime.getRuntime().addShutdownHook(
                new Thread( "HubService shutdown" ) {
            public void run() {
                shutdown();
            }
        } );
    }

    public String getPassword() {
        return password_;
    }

    public Map register( Object auth ) throws SampException {
        if ( password_.equals( auth ) ) {
            Client client = new Client( keyGen_.next(), "c" + ++clientCount_ );
            clientSet_.add( client );
            hubEvent( new Message( "samp.hub.event.register" )
                         .addParam( "id", client.getPublicId() ) );
            return new RegInfo( hubClient_.getPublicId(), client.getPublicId(),
                                client.getPrivateKey() );
        }
        else {
            throw new SampException( "Bad password" );
        }
    }

    public void unregister( Object id ) throws SampException {
        Client caller = getCaller( id );
        clientSet_.remove( caller );
        hubEvent( new Message( "samp.hub.event.unregister" )
                     .addParam( "id", caller.getPublicId() ) );
    }

    public void declareMetadata( Object id, Map meta ) throws SampException {
        Client caller = getCaller( id );
        caller.setMetadata( meta );
        hubEvent( new Message( "samp.hub.event.metadata" )
                     .addParam( "id", caller.getPublicId() )
                     .addParam( "metadata", meta ) );
    }

    public Map getMetadata( Object id, String clientId )
            throws SampException {
        checkCaller( id );
        return getClient( clientId ).getMetadata();
    }

    public void declareSubscriptions( Object id, Map subscriptions )
            throws SampException {
        Client caller = getCaller( id );
        if ( caller.isCallable() ) {
            caller.setSubscriptions( subscriptions );
            hubEvent( new Message( "samp.hub.event.subscriptions" )
                         .addParam( "id", caller.getPublicId() )
                         .addParam( "subscriptions", subscriptions ) );
        }
        else {
            throw new SampException( "Client is not callable" );
        }
    }

    public Map getSubscriptions( Object id, String clientId ) 
            throws SampException {
        checkCaller( id );
        return getClient( clientId ).getSubscriptions();
    }

    public List getRegisteredClients( Object id ) throws SampException {
        checkCaller( id );
        Client[] clients = clientSet_.getClients();
        List idList = new ArrayList( clients.length );
        for ( int ic = 0; ic < clients.length; ic++ ) {
            idList.add( clients[ ic ].getPublicId() );
        }
        return idList;
    }

    public Map getSubscribedClients( Object id, String mtype )
            throws SampException {
        Client[] clients = clientSet_.getClients();
        Map subMap = new TreeMap(); 
        for ( int ic = 0; ic < clients.length; ic++ ) {
            Client client = clients[ ic ];
            Map sub = client.getSubscriptions().getSubscription( mtype );
            if ( sub != null ) {
                subMap.put( client.getPublicId(), sub );
            }
        }
        return subMap;
    }

    public void notify( Object id, String recipientId, Map message )
            throws SampException {
        Client caller = getCaller( id );
        Message msg = Message.asMessage( message );
        String mtype = msg.getMType();
        Client recipient = getClient( recipientId );
        if ( recipient.getSubscriptions().isSubscribed( mtype ) ) {
            recipient.getCallable()
                     .receiveNotification( caller.getPublicId(), msg );
        }
        else {
            throw new SampException( "Client " + recipient
                                   + " not subscribed to " + mtype );
        }
    }

    public String call( Object id, String recipientId, String msgTag,
                        Map message )
            throws SampException {
        Client caller = getCaller( id );
        Message msg = Message.asMessage( message );
        String mtype = msg.getMType();
        Client recipient = getClient( recipientId );
        String msgId = MessageId.encode( caller, msgTag, false );
        if ( recipient.getSubscriptions().isSubscribed( mtype ) ) {
            recipient.getCallable()
                     .receiveCall( caller.getPublicId(), msgId, msg );
        }
        else {
            throw new SampException( "Client " + recipient
                                   + " not subscribed to " + mtype );
        }
        return msgId;
    }

    public void notifyAll( Object id, Map message ) throws SampException {
        Client caller = getCaller( id );
        Message msg = Message.asMessage( message );
        String mtype = msg.getMType();
        Client[] recipients = clientSet_.getClients();
        for ( int ic = 0; ic < recipients.length; ic++ ) {
            Client recipient = recipients[ ic ];
            if ( recipient != caller && recipient.isSubscribed( mtype ) ) {
                try {
                    recipient.getCallable()
                             .receiveNotification( caller.getPublicId(), msg );
                }
                catch ( SampException e ) {
                    logger_.log( Level.WARNING, 
                                 "Notification " + caller + " -> " + recipient
                               + " failed: " + e, e );
                }
            }
        }
    }

    public String callAll( Object id, String msgTag, Map message )
            throws SampException {
        Client caller = getCaller( id );
        Message msg = Message.asMessage( message );
        String mtype = msg.getMType();
        String msgId = MessageId.encode( caller, msgTag, false );
        Client[] recipients = clientSet_.getClients();
        for ( int ic = 0; ic < recipients.length; ic++ ) {
            Client recipient = recipients[ ic ];
            if ( recipient != caller && recipient.isSubscribed( mtype ) ) {
                recipient.getCallable()
                         .receiveCall( caller.getPublicId(), msgId, msg );
            }
        }
        return msgId;
    }

    public void reply( Object id, String msgIdStr, Map response )
            throws SampException {
        Client caller = getCaller( id );
        Response resp = Response.asResponse( response );
        MessageId msgId = MessageId.decode( msgIdStr );
        Client sender = getClient( msgId.getSenderId() );
        String senderTag = msgId.getSenderTag();
        if ( msgId.isSynch() ) {
            synchronized ( waiterMap_ ) {
                if ( waiterMap_.containsKey( msgId ) ) {
                    if ( waiterMap_.get( msgId ) == null ) {
                         waiterMap_.put( msgId, resp );
                         waiterMap_.notifyAll();
                    }
                    else {
                        throw new SampException( "Response ignored"
                                               + " - you've already sent one" );
                    }
                }
                else {
                    throw new SampException( "Response ignored"
                                           + " - synchronous call timed out" );
                }
            }
        }
        else {
            sender.getCallable()
                  .receiveResponse( caller.getPublicId(), senderTag, resp );
        }
    }

    public Map callAndWait( Object id, String recipientId, Map message,
                            String timeoutStr )
            throws SampException {
        Client caller = getCaller( id );
        Message msg = Message.asMessage( message );
        String mtype = msg.getMType();
        Client recipient = getClient( recipientId );
        MessageId hubMsgId =
            new MessageId( caller.getPublicId(), keyGen_.next(), true );
        int timeout;
        try { 
            timeout = SampUtils.decodeInt( timeoutStr );
        }
        catch ( Exception e ) {
            throw new SampException( "Bad timeout format (should be SAMP int)",
                                     e );
        }
        if ( recipient.getSubscriptions().isSubscribed( mtype ) ) {
            recipient.getCallable()
                     .receiveCall( caller.getPublicId(), hubMsgId.toString(),
                                   msg );
            timeout = Math.min( Math.max( 0, timeout ),
                                Math.max( 0, MAX_TIMEOUT ) );
            long finish = timeout > 0
                        ? System.currentTimeMillis() + timeout * 1000
                        : Long.MAX_VALUE;  // 3e8 years
            synchronized ( waiterMap_ ) {
                if ( MAX_WAITERS > 0 && waiterMap_.size() >= MAX_WAITERS ) {
                    while ( waiterMap_.size() >= MAX_WAITERS ) {
                        waiterMap_.remove( waiterMap_.firstKey() );
                    }
                    waiterMap_.notifyAll();
                }
                waiterMap_.put( hubMsgId, null );
                while ( waiterMap_.containsKey( hubMsgId ) &&
                        waiterMap_.get( hubMsgId ) == null &&
                        System.currentTimeMillis() < finish ) {
                    long millis = finish - System.currentTimeMillis();
                    if ( millis > 0 ) {
                        try {
                            waiterMap_.wait( millis );
                        }
                        catch ( InterruptedException e ) {
                            throw new SampException( "Wait interrupted", e );
                        }
                    }
                }
                if ( waiterMap_.containsKey( hubMsgId ) ) {
                    Response response =
                        (Response) waiterMap_.remove( hubMsgId );
                    if ( response != null ) {
                        return response;
                    }
                    else {
                        assert System.currentTimeMillis() >= finish;
                        throw new SampException( "Synchronous call timeout" );
                    }
                }
                else {
                    throw new SampException( "Synchronous call aborted - "
                                           + "server load exceeded maximum?" );
                }
            }
        }
        else {
            throw new SampException( "Client " + recipient
                                   + " not subscribed to " + mtype );
        }
    }

    public synchronized void shutdown() {
        if ( ! shutdown_ ) {
            shutdown_ = true;
            hubEvent( new Message( "samp.hub.event.shutdown" ) );
        }
    }

    private void hubEvent( Message msg ) {
        try {
            notifyAll( hubClient_.getPrivateKey(), msg );
        }
        catch ( SampException e ) {
            assert false;
        }
    }

    private void checkCaller( Object id ) throws SampException {
        getCaller( id );
    }

    private Client getCaller( Object id ) throws SampException {
        Client caller = clientSet_.getFromPrivateKey( (String) id );
        if ( caller != null ) {
            return caller;
        }
        else {
            throw new SampException( "Invalid key for caller" );
        }
    }

    private Client getClient( String id ) throws SampException {
        Client client = clientSet_.getFromPublicId( id );
        if ( client != null ) {
            return client;
        }
        else {
  // check here if this used to be registered, and alter message accordingly
            throw new SampException( "No registered client with ID \""
                                   + id + "\"" );
        }
    }

    private static class ClientSet {

        private final Map publicIdMap_ = new TreeMap();
        private final Map privateKeyMap_ = new HashMap();

        public synchronized void add( Client client ) {
            assert client.getPublicId().indexOf( ID_DELIMITER ) < 0;
            publicIdMap_.put( client.getPublicId(), client );
            privateKeyMap_.put( client.getPrivateKey(), client );
        }

        public synchronized void remove( Client client ) {
            publicIdMap_.remove( client.getPublicId() );
            privateKeyMap_.remove( client.getPrivateKey() );
        }

        public synchronized Client getFromPublicId( String publicId ) {
            return (Client) publicIdMap_.get( publicId );
        }

        public synchronized Client getFromPrivateKey( String privateKey ) {
            return (Client) privateKeyMap_.get( privateKey );
        }

        public synchronized Client[] getClients() {
            return (Client[]) publicIdMap_.values().toArray( new Client[ 0 ] );
        }
    }

    private static class MessageId {

        private final String senderId_;
        private final String senderTag_;
        private final boolean isSynch_;
        private final long birthday_;

        private static final String T_SYNCH_FLAG = "S";
        private static final String F_SYNCH_FLAG = "A";
        private static final int CHECK_SEED = (int) System.currentTimeMillis();
        private static final int CHECK_LENG = 4;
        private static final Comparator AGE_COMPARATOR = new Comparator() {
            public int compare( Object o1, Object o2 ) {
                return (int) (((MessageId) o1).birthday_ -
                              ((MessageId) o2).birthday_);
            }
        };

        public MessageId( String senderId, String senderTag, boolean isSynch ) {
            senderId_ = senderId;
            senderTag_ = senderTag;
            isSynch_ = isSynch;
            birthday_ = System.currentTimeMillis();
        }

        public String getSenderId() {
            return senderId_;
        }

        public String getSenderTag() {
            return senderTag_;
        }

        public boolean isSynch() {
            return isSynch_;
        }

        public int hashCode() {
            return checksum( senderId_, senderTag_, isSynch_ ).hashCode();
        }

        public boolean equals( Object o ) {
            if ( o instanceof MessageId ) {
                MessageId other = (MessageId) o;
                return this.senderId_.equals( other.senderId_ )
                    && this.senderTag_.equals( other.senderTag_ )
                    && this.isSynch_ == other.isSynch_;
            }
            else {
                return false;
            }
        }

        public String toString() {
            Object checksum = checksum( senderId_, senderTag_, isSynch_ );
            return new StringBuffer()
                  .append( senderId_ )
                  .append( ID_DELIMITER )
                  .append( isSynch_ ? T_SYNCH_FLAG : F_SYNCH_FLAG )
                  .append( ID_DELIMITER )
                  .append( checksum )
                  .append( ID_DELIMITER )
                  .append( senderTag_ )
                  .toString();
        }

        public static MessageId decode( String msgId )
                throws SampException {
            int delim1 = msgId.indexOf( ID_DELIMITER );
            int delim2 = msgId.indexOf( ID_DELIMITER, delim1 + 1 );
            int delim3 = msgId.indexOf( ID_DELIMITER, delim2 + 1 );
            if ( delim1 < 0 || delim2 < 0 || delim3 < 0 ) {
                throw new SampException( "Badly formed message ID" );
            }
            String senderId = msgId.substring( 0, delim1 );
            String synchFlag = msgId.substring( delim1 + 1, delim2 );
            String checksum = msgId.substring( delim2 + 1, delim3 );
            String senderTag = msgId.substring( delim3 + 1 );
            boolean isSynch;
            if ( T_SYNCH_FLAG.equals( synchFlag ) ) {
                isSynch = true;
            }
            else if ( F_SYNCH_FLAG.equals( synchFlag ) ) {
                isSynch = false;
            }
            else {
                throw new SampException( "Badly formed message ID"
                                       + " (synch flag)" );
            }
            if ( ! checksum( senderId, senderTag, isSynch )
                  .equals( checksum ) ) {
                throw new SampException( "Bad message ID checksum" );
            }
            MessageId idObj = new MessageId( senderId, senderTag, isSynch );
            assert idObj.toString().equals( msgId );
            return idObj;
        }

        public static String encode( Client sender, String senderTag,
                                     boolean isSynch ) {
            return new MessageId( sender.getPublicId(), senderTag, isSynch )
                  .toString();
        }

        private static String checksum( String senderId, String senderTag,
                                        boolean isSynch ) {
            int sum = CHECK_SEED;
            sum = 23 * sum + senderId.hashCode();
            sum = 23 * sum + senderTag.hashCode();
            sum = 23 * sum + ( isSynch ? 3 : 5 );
            String check = Integer.toHexString( sum );
            check =
                check.substring( Math.max( 0, check.length() - CHECK_LENG ) );
            while ( check.length() < CHECK_LENG ) {
                check = "0" + check;
            }
            assert check.length() == CHECK_LENG;
            return check;
        }
    }

    private static class KeyGenerator {

        private final String prefix_;
        private final int nchar_;
        private final Random random_;
        private int iseq_;
        private final char unused_;

        public KeyGenerator( String prefix, int nchar, Random random ) {
            prefix_ = prefix;
            nchar_ = nchar;
            random_ = random;
            unused_ = '_';
        }

        public synchronized String next() {
            StringBuffer sbuf = new StringBuffer();
            sbuf.append( prefix_ );
            sbuf.append( Integer.toString( ++iseq_ ) );
            sbuf.append( '_' );
            for ( int i = 0; i < nchar_; i++ ) {
                char c = (char) ( 'a' + (char) random_.nextInt( 'z' - 'a' ) );
                assert c != ID_DELIMITER;
                sbuf.append( c );
            }
            return sbuf.toString();
        }

        public char getUnusedChar() {
            return unused_;
        }
    }
}
