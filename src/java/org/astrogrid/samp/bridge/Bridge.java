package org.astrogrid.samp.bridge;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.astrogrid.samp.LockInfo;
import org.astrogrid.samp.SampUtils;
import org.astrogrid.samp.client.ClientProfile;
import org.astrogrid.samp.client.HubConnection;
import org.astrogrid.samp.client.HubConnector;
import org.astrogrid.samp.client.SampException;
import org.astrogrid.samp.httpd.UtilServer;
import org.astrogrid.samp.xmlrpc.StandardClientProfile;
import org.astrogrid.samp.xmlrpc.XmlRpcKit;

/**
 * Runs a bridging service between two or more hubs.
 * For each client on one hub, a proxy client appears on all other 
 * participating hubs.  These proxies can be treated in exactly the
 * same way as normal clients by other registered clients; any
 * messages sent to/from them will be marshalled over the bridge 
 * in a transparent way.  One application for this is to allow 
 * collaboration between users who each have their own hub running.
 *
 * @author   Mark Taylor
 * @since    15 Jul 2009
 */
public class Bridge {

    private final ProxyManager[] proxyManagers_;

    /**
     * Constructor.
     *
     * @param   profiles   array of SAMP profile objects, one for each
     *          hub which is to participate in the bridge
     */
    public Bridge( ClientProfile[] profiles ) throws IOException {
        int nhub = profiles.length;
        proxyManagers_ = new ProxyManager[ nhub ];
        UtilServer server = UtilServer.getInstance();
        for ( int ih = 0; ih < nhub; ih++ ) {
            proxyManagers_[ ih ] = new ProxyManager( profiles[ ih ], server );
        }
        for ( int ih = 0; ih < nhub; ih++ ) {
            proxyManagers_[ ih ].init( proxyManagers_ );
        }
        for ( int ih = 0; ih < nhub; ih++ ) {
            proxyManagers_[ ih ].getManagerConnector().setAutoconnect( 0 );
        }
    }

    /**
     * Returns the client profiles which define the hubs this bridge links.
     *
     * @return  profile array, one for each connected hub
     */
    public ClientProfile[] getProfiles() {
        int nhub = proxyManagers_.length;
        ClientProfile[] profiles = new ClientProfile[ nhub ];
        for ( int ih = 0; ih < nhub; ih++ ) {
            profiles[ ih ] = proxyManagers_[ ih ].getProfile();
        }
        return profiles;
    }

    /**
     * Returns the hub connectors representing the bridge client running
     * on each linked hub.  Note this does not include any proxy clients,
     * only the one-per-hub manager clients.
     *
     * @return   array of bridge manager clients, one for each hub
     *           (in corresponding positions to the profiles)
     */
    public HubConnector[] getBridgeClients() {
        int nhub = proxyManagers_.length;
        HubConnector[] connectors = new HubConnector[ nhub ];
        for ( int ih = 0; ih < nhub; ih++ ) {
            connectors[ ih ] = proxyManagers_[ ih ].getManagerConnector();
        }
        return connectors;
    }

    /**
     * Sets up a URL exporter for one of the hubs.  This will attempt to 
     * edit transmitted data contents for use in remote contexts;
     * the main job is to adjust loopback host references in URLs
     * (127.0.0.1 or localhost) to become fully qualified domain names
     * for non-local use.  It's not an exact science, but a best effort
     * is made.
     *
     * @param  index  index of the profile for which to export URLs
     * @param  host   the name substitute for loopback host identifiers
     *                on the host on which that profile's hub is running
     */
    public void exportUrls( int index, String host ) {
        proxyManagers_[ index ].setExporter( new UrlExporter( host ) );
    }

    /**
     * Starts this bridge running.
     * 
     * @return  true iff all the participating hubs have been contacted
     *          successfully
     */
    public boolean start() {
        HubConnector[] connectors = getBridgeClients();
        boolean allConnected = true;
        for ( int ih = 0; ih < connectors.length; ih++ ) {
            HubConnector connector = connectors[ ih ];
            connector.setActive( true );
            allConnected = allConnected && connector.isConnected();
        }
        return allConnected;
    }

    /**
     * Stops this bridge running.
     * All associated manager and proxy clients are unregistered.
     */
    public void stop() {
        HubConnector[] connectors = getBridgeClients();
        for ( int ih = 0; ih < connectors.length; ih++ ) {
            connectors[ ih ].setActive( false );
        }
    }

    /**
     * Main method.  Runs a bridge.
     */
    public static void main( String[] args ) throws IOException {

        // Unless specially requested, make sure that the local host 
        // is referred to by something publicly useful, not the loopback
        // address, which would be no good if there will be communications
        // to/from an external host.
        String hostspec = System.getProperty( SampUtils.LOCALHOST_PROP );
        if ( hostspec == null ) {
            System.setProperty( SampUtils.LOCALHOST_PROP, "[hostname]" );
        }

        // Run the application.
        int status = runMain( args );
        if ( status != 0 ) {
            System.exit( status );
        }
    }

    /**
     * Does the work for the main method.
     * Use -help flag.
     */
    public static int runMain( String[] args ) throws IOException {
        String usage = new StringBuffer()
            .append( "\n   Usage:" )
            .append( "\n      " )
            .append( Bridge.class.getName() )
            .append( "\n         " )
            .append( " [-help]" )
            .append( " [-/+verbose]" )
            .append( " [-noexporturls]" )
            .append( "\n         " )
            .append( " [-nostandard]" )
            .append( " [-sampdir <lockfile-dir>]" )
            .append( " [-sampfile <lockfile>]" )
            .append( "\n         " )
            .append( " [-keys <xmlrpc-url> <secret>]" )
            .append( " [-profile <clientprofile-class>]" )
            .append( "\n" )
            .toString();
        List argList = new ArrayList( Arrays.asList( args ) );

        // Handle administrative flags - best done before other parameters.
        int verbAdjust = 0;
        for ( Iterator it = argList.iterator(); it.hasNext(); ) {
            String arg = (String) it.next();
            if ( arg.equals( "-v" ) || arg.equals( "-verbose" ) ) {
                it.remove();
                verbAdjust--;
            }
            else if ( arg.equals( "+v" ) || arg.equals( "+verbose" ) ) {
                it.remove();
                verbAdjust++;
            }
            else if ( arg.equals( "-h" ) || arg.equals( "-help" ) 
                                         || arg.equals( "--help" ) ) {
                it.remove();
                System.out.println( usage );
                return 0;
            }
        }

        // Adjust logging in accordance with verbosity flags.
        int logLevel = Level.WARNING.intValue() + 100 * verbAdjust;
        Logger.getLogger( "org.astrogrid.samp" )
              .setLevel( Level.parse( Integer.toString( logLevel ) ) );

        // Assemble list of profiles to use from command line arguments.
        List profileList = new ArrayList();
        XmlRpcKit xmlrpcKit = XmlRpcKit.getInstance();
        ClientProfile standardProfile = new ClientProfile() {
            public HubConnection register() throws SampException {
                return StandardClientProfile.getInstance().register();
            }
            public String toString() {
                return "standard";
            }
        };
        profileList.add( standardProfile );
        boolean exporturls = true;
        for ( Iterator it = argList.iterator(); it.hasNext(); ) {
            String arg = (String) it.next();

            // Determine whether to export localhost-type URLs.
            if ( arg.equals( "-exporturls" ) ) {
                exporturls = true;
            }
            else if ( arg.equals( "-noexporturls" ) ) {
                exporturls = false;
            }

            // Accumulate various profiles.
            else if ( arg.equals( "-standard" ) ) {
                it.remove();
                profileList.remove( standardProfile );
                profileList.add( standardProfile );
            }
            else if ( arg.equals( "-nostandard" ) ) {
                it.remove();
                profileList.remove( standardProfile );
            }
            else if ( arg.equals( "-sampfile" ) && it.hasNext() ) {
                it.remove();
                String fname = (String) it.next();
                it.remove();
                final File lockfile = new File( fname );
                profileList.add( new StandardClientProfile( xmlrpcKit ) {
                    public LockInfo getLockInfo() throws IOException {
                        return LockInfo.readLockFile( lockfile );
                    }
                    public String toString() {
                        return lockfile.toString();
                    }
                } );
            }
            else if ( arg.equals( "-sampdir" ) && it.hasNext() ) {
                it.remove();
                final String dirname = (String) it.next();
                it.remove();
                final File lockfile =
                    new File( dirname, SampUtils.LOCKFILE_NAME );
                profileList.add( new StandardClientProfile( xmlrpcKit ) {
                    public LockInfo getLockInfo() throws IOException {
                        return LockInfo.readLockFile( lockfile );
                    }
                    public String toString() {
                        return dirname;
                    }
                } );
            }
            else if ( arg.equals( "-keys" ) && it.hasNext() ) {
                it.remove();
                String endpoint = (String) it.next();
                final URL url;
                try {
                    url = new URL( endpoint );
                }
                catch ( MalformedURLException e ) {
                    System.err.println( "Not a URL: " + endpoint );
                    System.err.println( usage );
                    return 1;
                }
                it.remove();
                if ( ! it.hasNext() ) {
                    System.err.println( usage );
                    return 1;
                }
                final String secret = (String) it.next();
                it.remove();
                profileList.add( new StandardClientProfile( xmlrpcKit ) {
                    public LockInfo getLockInfo() throws IOException {
                        return new LockInfo( secret, url.toString() );
                    }
                    public String toString() {
                        return url.toString();
                    }
                } );
            }
            else if ( arg.equals( "-profile" ) && it.hasNext() ) {
                it.remove();
                String cname = (String) it.next();
                it.remove();
                final ClientProfile profile;
                try {
                    profile =
                        (ClientProfile) Class.forName( cname ).newInstance();
                }
                catch ( Exception e ) {
                    System.err.println( "Error instantiating class " + cname 
                                      + "; " + e );
                    System.err.println( usage );
                    return 1;
                }
                profileList.add( profile );
            }
            else {
                it.remove();
                System.err.println( usage );
                return 1;
            }
        }
        assert argList.isEmpty();

        // Get the array of profiles to bridge between.
        ClientProfile[] profiles =
            (ClientProfile[]) profileList.toArray( new ClientProfile[ 0 ] );
        if ( profiles.length < 2 ) {
            System.err.println( ( profiles.length == 0 ? "No" : "Only one" )
                              + " hub specified - no bridging to be done" );
            if ( args.length == 0 ) {
                System.err.println( usage );
            }
            return 1;
        }

        // Create a bridge.
        Bridge bridge = new Bridge( profiles );

        // Arrange to export URLs if requested.
        if ( exporturls ) {
            for ( int ip = 0; ip < profiles.length; ip++ ) {
                ClientProfile profile = profiles[ ip ];
                String host = null;
                if ( profile == standardProfile ) {
                    host = SampUtils.getLocalhost();
                }
                else if ( profile instanceof StandardClientProfile ) {
                    URL xurl = ((StandardClientProfile) profile).getLockInfo()
                                                                .getXmlrpcUrl();
                    if ( xurl != null ) {
                        host = xurl.getHost();
                    }
                }
                if ( host != null ) {
                    InetAddress addr = InetAddress.getByName( host );
                    if ( addr.isLoopbackAddress() ) {
                        addr = InetAddress
                              .getByName( SampUtils.getLocalhost() );
                    }
                    String ehost = addr.getCanonicalHostName();
                    bridge.exportUrls( ip, ehost );
                }
            }
        }

        // Start the bridge.
        if ( ! bridge.start() ) {
            System.err.println( "Couldn't contact all hubs" );
            return 1;
        }

        // Wait indefinitely.
        Object lock = new String( "Forever" );
        synchronized ( lock ) {
            try {
                lock.wait();
            }
            catch ( InterruptedException e ) {
            }
        }
        return 0;
    }
}
