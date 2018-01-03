package com.redhat.qe.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.redhat.qe.jul.TestRecords;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

public class SSHCommandRunner implements Runnable {

	protected SSHClient connection;
	protected String user = null;
	protected Session session;
	protected InputStream out;
	protected static Logger log = Logger.getLogger(SSHCommandRunner.class.getName());


	protected InputStream err;
	protected String s_out = null;
	protected String s_err = null;
	protected boolean kill = false;
	protected String command = null;
	protected Object lock = new Object();
	protected Long emergencyTimeoutMS = 1000l;
	protected Integer exitCode;
	protected Command actuallCommand = null;


	public SSHCommandRunner(SSHClient connection,
			String command) {
		super();
		this.connection = connection;
		this.command = command;
    this.emergencyTimeoutMS = Long.parseLong(System.getProperty("ssh.emergencyTimeoutMS","1000"));
	}
	
	
	public SSHCommandRunner(String server,
			String user,
			File sshPemFile,
			String passphrase,
			String command) throws IOException{
		super();
		SSHClient ssh = new SSHClient();
		ssh.loadKnownHosts();
		ssh.connect(server);
		KeyProvider keyProvider = ssh.loadKeys(sshPemFile.toString(), passphrase);
		ssh.authPublickey(user, keyProvider);
		if(!ssh.isAuthenticated()) {
			throw new RuntimeException("Could not log in to " + ssh.getRemoteHostname() + " with the given credentials ("+user+").");						
		}
		this.connection = ssh;
		this.user = user;
		this.command = command;
    this.emergencyTimeoutMS = Long.parseLong(System.getProperty("ssh.emergencyTimeoutMS","1000"));
	}

	public SSHCommandRunner(String server,
			String user,
			String passphrase,
			File sshPemFile,
			String pemPassphrase,
			String command) throws IOException{
		super();
		SSHClient ssh = new SSHClient();
		ssh.loadKnownHosts();
		ssh.connect(server);
		KeyProvider keyProvider = ssh.loadKeys(sshPemFile.toString(), passphrase);
		ssh.authPublickey(user, keyProvider);
		if(!ssh.isAuthenticated()) {
			ssh.authPassword(user, passphrase);
			if (!ssh.isAuthenticated()) {
				throw new RuntimeException("Could not log in to " + ssh.getRemoteHostname() + " with the given credentials ("+user+").");	
			}
		}
		this.connection = ssh;
		this.user = user;
		this.command = command;
    this.emergencyTimeoutMS = Long.parseLong(System.getProperty("ssh.emergencyTimeoutMS","1000"));
	}
	
	public SSHCommandRunner(String server,
			String user,
			String password,
			String command) throws IOException{
		super();
		SSHClient ssh = new SSHClient();
		ssh.loadKnownHosts();
		ssh.connect(server);
		ssh.authPassword(user, password);
		if (!ssh.isAuthenticated()) {
				throw new RuntimeException("Could not log in to " + ssh.getRemoteHostname() + " with the given credentials ("+user+").");
		}
		this.connection = ssh;
		this.user = user;
		this.command = command;
    this.emergencyTimeoutMS = Long.parseLong(System.getProperty("ssh.emergencyTimeoutMS","1000"));
	}

	public SSHCommandRunner(String server,
			String user,
			String sshPemFile,
			String passphrase,
			String command) throws IOException{
		this(server, user, new File(sshPemFile), passphrase, command);
	}

	public SSHCommandRunner(String server,
			String user,
			String passphrase,
			String sshPemFile,
			String pemPassphrase,
			String command) throws IOException{
		this(server, user, passphrase, new File(sshPemFile), pemPassphrase, command);
	}

	
	public void run(LogRecord logRecord) {
		try {
			if (logRecord == null) logRecord = TestRecords.fine();
			
			/*
			 * Sync'd block prevents other threads from getting the streams before they've been set up here.
			 */
			synchronized (lock) {
//				log.info("SSH: Running '"+this.command+"' on '"+this.connection.getHostname()+"'");
				String message = "ssh "+ connection.getRemoteHostname()+ " " + command;
				if (this.user!=null) message = "ssh "+ user +"@"+ connection.getRemoteHostname()+" "+ command;
				logRecord.setMessage(message);
				log.log(logRecord);
				session = connection.startSession();
				actuallCommand = session.exec(new String(command.getBytes("UTF-8"), "ISO-8859-1"));
				actuallCommand.join(emergencyTimeoutMS,TimeUnit.MILLISECONDS);
				out = actuallCommand.getInputStream();
				err = actuallCommand.getErrorStream();
			}			

		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public void run() {
		run(TestRecords.action());
	}
	
	public Integer waitFor(){
		return waitForWithTimeout(emergencyTimeoutMS);
	}
	
	/**
	 * @param timeoutMS - time out, in milliseconds
	 * @return null if command was interrupted or timedout, the command return code otherwise
	 */
	public Integer waitForWithTimeout(Long timeoutMS) {
		try {
			session.join(timeoutMS, TimeUnit.MILLISECONDS);
			this.exitCode = actuallCommand.getExitStatus();
			this.out = actuallCommand.getInputStream();// actuallCommand.getInputtream();
			this.err = actuallCommand.getErrorStream();
			actuallCommand.close();
			session.close();
			return this.exitCode;
		} catch (ConnectionException ex) {
			return null;
		} catch (TransportException ex) {
			return null;
		}
	}
	
	public boolean isDone(){
		if (session == null)
			return false;
		if (getExitCode() == null) 
			return false;
		return true;
	}

	protected String convertStreamToString(InputStream is) {
		/*
		 * To convert the InputStream to String we use the
		 * BufferedReader.readLine() method. We iterate until the BufferedReader
		 * return null which means there's no more data to read. Each line will
		 * appended to a StringBuilder and returned as String.
		 */
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		StringBuilder sb = new StringBuilder();

		String line = null;
		try {
			while ((line = reader.readLine()) != null) {
				sb.append(line + "\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		return sb.toString();
	}
	
	
	public SSHCommandResult getSSHCommandResult() {
		return new SSHCommandResult(getExitCode(),getStdout(),getStderr());
	}
	
	
	public Integer getExitCode() {
		return this.exitCode;
	}

	public int getFreeLocalPort() throws IOException {
    ServerSocket freeSocket = null;
    int freePort;
    try {
      freeSocket = new ServerSocket(0);
      freePort = freeSocket.getLocalPort();
    } finally {
      if (freeSocket != null) {
        freeSocket.close();
      }
    }
    return freePort;
  }

	/**
	 * Consumes entire stdout stream of the command, this will block until the stream is closed.
	 * @return entire contents of stdout stream
	 */
	public String getStdout() {
		synchronized (lock) {
			if (s_out == null) s_out = convertStreamToString(out);
			return s_out;
		}
	}

	/**
	 * Consumes entire stderr stream of the command, this will block until the stream is closed.
	 * @return entire contents of stderr stream
	 */
	public String getStderr() {
		synchronized (lock) {
			if (s_err == null) s_err = convertStreamToString(err);
			return s_err;
		}
	}
	
	/**
	 * When an emergency timeout is not set, it is possible that the run command will never return.
	 * Use this method to set an upper limit to prevent the run command from unexpectedly getting stuck.
	 * If the emergencyTimeout is reached, then the SSHCommandResult should contain an exitCode value of null.
	 * Pass null to unset the emergencyTimeout.
	 * @param emergencyTimeoutMS - in milliseconds
	 */
	public void setEmergencyTimeout(Long emergencyTimeoutMS) {
		this.emergencyTimeoutMS = emergencyTimeoutMS;
	}
	
	public void setCommand(String command) {
		reset();
		this.command = command;
	}

	public String getCommand() {
		return command;
	}
	
	public void runCommand(String command){
		runCommand(command,TestRecords.fine());
	}
	
	public void runCommand(String command, LogRecord logRecord){
		reset();
		this.command = command;
		run(logRecord);
	}
	
	public SSHCommandResult runCommandAndWait(String command){
		return runCommandAndWait(command,emergencyTimeoutMS,TestRecords.fine(), false, true);
	}
	
	public SSHCommandResult runCommandAndWait(String command, boolean liveLogOutput){
		return runCommandAndWait(command,emergencyTimeoutMS,TestRecords.fine(), liveLogOutput, true);
	}
	
	public SSHCommandResult runCommandAndWait(String command, Long timeoutMS){
		return runCommandAndWait(command,timeoutMS,TestRecords.fine(), false, true);
	}
	
	public SSHCommandResult runCommandAndWait(String command, LogRecord logRecord){
		return runCommandAndWait(command,emergencyTimeoutMS,logRecord, false, true);
	}
	
	public SSHCommandResult runCommandAndWaitWithoutLogging(String command){
		return runCommandAndWait(command,emergencyTimeoutMS,TestRecords.fine(), false, false);
	}
	
	/**
	 * @param command - the remote command to run
	 * @param timeoutMS - abort if command doesn't complete in this many milliseconds 
	 * 	(null means wait for command to complete, no matter how long it takes) 
	 * @param logRecord - a log record whose Level and Parameters will be used to do all
	 * the command output logging.  eg, a logRecord whose Level is INFO means log all the
	 * output at INFO level.  
	 * @param liveLogOutput - if true, log output as the command runs.  Good for long running
	 * commands, or commands that could potentially hang or timeout.  If false, don't log 
	 * any output until the command has finished running.
	 * @param logOutput - if false, the stdout, stderr, and exitCode will not be logged at all
	 * @return the integer return code of the command
	 */ 
	public SSHCommandResult runCommandAndWait(String command, Long timeoutMS, LogRecord logRecord, boolean liveLogOutput, boolean logOutput){
		runCommand(command,logRecord);
		SplitStreamLogger logger = null;
		if (liveLogOutput && logOutput){
			logger = new SplitStreamLogger(this);
			logger.log(logRecord.getLevel(), logRecord.getLevel());
		}
		waitForWithTimeout(timeoutMS);
		SSHCommandResult sshCommandResult = null;
		if (liveLogOutput && logOutput) {
			s_out = logger.getStdout();
			s_err = logger.getStderr();
		}
		
		sshCommandResult = getSSHCommandResult();
		
		if (!liveLogOutput && logOutput){
			String o = (this.getStdout().split("\n").length>1)? "\n":"";
			String e = (this.getStderr().split("\n").length>1)? "\n":"";
			log.log(logRecord.getLevel(), "Stdout: "+o+sshCommandResult.getStdout());
			log.log(logRecord.getLevel(), "Stderr: "+e+sshCommandResult.getStderr());
		}
		
		if (logOutput){
			log.log(logRecord.getLevel(), "ExitCode: "+sshCommandResult.getExitCode());
		}

		return sshCommandResult;
	}
	
	
	
	/**
	 * Stop waiting for the command to complete.
	 */
	public synchronized void kill(){
		kill= true;
	}
	
	public InputStream getStdoutStream() {		
		synchronized (lock) {
			return out;
		}
	}

	public InputStream getStdErrStream() {		
		synchronized (lock) {
			return err;
		}
	}
	
	public void reset(){
		try {
			if (out!= null) out.close();
			if (err != null) err.close();
			if (session!= null)session.close();			
		}
		catch(IOException ioe) {
			log.log(Level.FINER, "Couldn't close input stream", ioe);
		}
		s_out = null;
		s_err = null;
		command = null;
	}

	
	public SSHClient getConnection() {
		return connection;
	}
	
	/**
	 * Runs a command via SSH as specified user, logs all output to INFO
	 * logging level, returns String[] containing stdout in 0 position
	 * and stderr in 1 position
	 * @param hostname hostname of system
	 * @param user user to execute command as
	 * @param command command to execute
	 * @return output as String[], stdout in 0 pos and stderr in 1 pos
	 */
	public static String[] executeViaSSHWithReturn(String hostname, 
			String user, String command){
		return executeViaSSHWithReturnWithTimeout(hostname,
				user,
				command,
				null);
	}
	
	/**
	 * Runs a command via SSH as specified user, logs all output to INFO
	 * logging level, returns String[] containing stdout in 0 position
	 * and stderr in 1 position
	 * @param hostname hostname of system
	 * @param user user to execute command as
	 * @param command command to execute
	 * @param timeout amount of time to wait for command completion, in seconds
	 * @return output as String[], stdout in 0 pos and stderr in 1 pos
	 */
	public static String[] executeViaSSHWithReturnWithTimeout(String hostname,
			String user, String command, Long timeoutMS){
		SSHCommandRunner runner = null;
		SplitStreamLogger logger;

//		log.info("SSH: Running '"+command+"' on '"+hostname+"'"); // moved log.info into run() method - jsefler 1/4/2010
		try{
			runner = new SSHCommandRunner(hostname,
					user,
					new File(System.getProperty("user.dir")+"/.ssh/id_auto_dsa"),
					System.getProperty("jon.server.sshkey.passphrase"),command);
			runner.run();
			logger = new SplitStreamLogger(runner);
			logger.log();
			Integer exitcode = runner.waitForWithTimeout(timeoutMS);
			
			if (exitcode == null){
				log.log(Level.INFO, "SSH command did not complete within timeout window");
				return failSSH();
			}
		}
		catch (Exception e){
			log.log(Level.INFO, "SSH command failed:", e);
			return failSSH();
		}
		return new String[] {logger.getStdout(), logger.getStderr()};
	}
	
	private static String[] failSSH(){
		return new String[] {"fail", "fail"};
	}
	


	/**
	 * Test code
	 * @param args
	 */
	public static void main(String[] args) throws Exception{

		/*Connection conn = new Connection("jweiss-rhel3.usersys.redhat.com");
		conn.connect();
		if (!conn.authenticateWithPassword("jonqa", "dog8code"))
			throw new IllegalStateException("Authentication failed.");
		SSHCommandRunner runner = new SSHCommandRunner(conn, "sleep 3");
		runner.run();
		Integer exitcode = runner.waitForWithTimeout(null);
		System.out.println("exit code: " + exitcode);*/
		

		//Logger log = Logger.getLogger(SSHCommandRunner.class.getName());
		SSHCommandRunner scr = new SSHCommandRunner("f14-1.usersys.redhat.com", "root", "dog8code", "sdf", "sdfs", null);
		scr.runCommandAndWait("sleep 5;echo 'hi there';sleep 3", true);
		System.out.println("Result: " + scr.getStdout());
		
		/*SCPClient scp = new SCPClient(conn);
		scp.put(System.getProperty("user.dir")+ "/../jon/bin/DummyJVM.class", "/tmp");
		SSHCommandRunner jrunner = new SSHCommandRunner(conn, "java -Dcom.sun.management.jmxremote.port=1500 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false -cp /tmp DummyJVM");

		jrunner.run();

		new SplitStreamLogger(jrunner).log();
		
		
		Thread.sleep(10000);
		SSHCommandRunner runner = new SSHCommandRunner(conn, "ps -ef | grep [D]ummy | awk '{print $2}'");
		runner.run();
		String pid = runner.getStdout().trim();
		log.info("Found pid " + pid);
		runner = new SSHCommandRunner(conn, "kill " + pid);
		runner.run();
		
		
		new SplitStreamLogger(runner).log();
		runner.waitFor();
		jrunner.waitFor();*/
		
		
		/*SSHCommandRunner jrunner = new SSHCommandRunner(conn, "grep sdf /tmp/sdsdfs");

		jrunner.run();
		System.out.println(jrunner.waitFor());*/
	/*	System.out.println("Output: " + runner.getStdout());
		System.out.println("Stderr: " + runner.getStderr());*/

	}

	

}
