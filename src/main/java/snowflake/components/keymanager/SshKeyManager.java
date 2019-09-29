package snowflake.components.keymanager;

import com.jcraft.jsch.*;
import snowflake.common.ssh.SshClient;
import snowflake.common.ssh.SshModalUserInteraction;
import snowflake.common.ssh.files.SshFileSystem;
import snowflake.components.newsession.SessionInfo;
import snowflake.utils.PathUtils;
import snowflake.utils.SshCommandUtils;

import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;

public class SshKeyManager {
    public static SshKeyHolder getKeyDetails(SessionInfo info, AtomicBoolean stopFlag) throws Exception {
        SshKeyHolder holder = new SshKeyHolder();
        loadLocalKey(getPubKeyPath(info), holder);
        loadRemoteKeys(holder, info);
        return holder;
    }

    private static void loadLocalKey(String pubKeyPath, SshKeyHolder holder) {
        try {
            Path defaultPath = pubKeyPath == null
                    ? Paths.get(System.getProperty("user.home"), ".ssh", "id_rsa.pub").toAbsolutePath()
                    : Paths.get(pubKeyPath);
            byte[] bytes = Files.readAllBytes(defaultPath);
            holder.setLocalPublicKey(new String(bytes, "utf-8"));
            holder.setLocalPubKeyFile(defaultPath.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadRemoteKeys(SshKeyHolder holder, SessionInfo info) throws Exception {
        try (SshFileSystem fileSystem = new SshFileSystem(new SshModalUserInteraction(info))) {
            fileSystem.connect();
            loadRemoteKeys(holder, fileSystem);
        }
    }

    private static void loadRemoteKeys(SshKeyHolder holder, SshFileSystem fileSystem) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String path = fileSystem.getHome() + "/.ssh/id_rsa.pub";
        fileSystem.getSftp().get(path, out);
        holder.setRemotePubKeyFile(path);
        holder.setRemotePublicKey(new String(out.toByteArray(), "utf-8"));
        out = new ByteArrayOutputStream();
        path = fileSystem.getHome() + "/.ssh/authorized_keys";
        try {
            fileSystem.getSftp().get(path, out);
            holder.setRemoteAuthorizedKeys(new String(out.toByteArray(), "utf-8"));
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
        }
    }

    public static void generateKeys(SshKeyHolder holder, SessionInfo info, AtomicBoolean stopFlag, boolean local) throws Exception {
        if (holder.getLocalPublicKey() != null) {
            if (JOptionPane.showConfirmDialog(null, "WARNING: This will overwrite the existing SSH key"
                            + "\n\nIf the key was being used to connect to other servers,"
                            + "\nconnection will fail."
                            + "\nYou have to reconfigure all the servers"
                            + "\nto use the new key\nDo you still want to continue?",
                    "Warning", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
        }

        JCheckBox chkGenPassPhrase = new JCheckBox("Use passphrase to protect private key (Optional)");
        JPasswordField txtPassPhrase = new JPasswordField(30);
        txtPassPhrase.setEditable(false);
        chkGenPassPhrase.addActionListener(e -> {
            txtPassPhrase.setEditable(chkGenPassPhrase.isSelected());
        });

        String passPhrase = new String(txtPassPhrase.getPassword());

        if (JOptionPane.showOptionDialog(null,
                new Object[]{chkGenPassPhrase, "Passphrase", txtPassPhrase},
                "Passphrase", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, null, null) == JOptionPane.YES_OPTION) {
            if (local) {
                generateLocalKeys(holder, passPhrase);
            } else {
                generateRemoteKeys(stopFlag, info, holder, passPhrase);
            }
        }
    }

    public static void generateLocalKeys(SshKeyHolder holder, String passPhrase) throws Exception {
        Path sshDir = Paths.get(System.getProperty("user.home"), ".ssh");
        Path pubKeyPath = Paths.get(System.getProperty("user.home"), ".ssh", "id_rsa.pub").toAbsolutePath();
        Path keyPath = Paths.get(System.getProperty("user.home"), ".ssh", "id_rsa").toAbsolutePath();
        JSch jsch = new JSch();
        KeyPair kpair = KeyPair.genKeyPair(jsch, KeyPair.RSA);
        Files.createDirectories(sshDir);
        if (passPhrase.length() > 0) {
            kpair.writePrivateKey(keyPath.toString(), passPhrase.getBytes("utf-8"));
        } else {
            kpair.writePrivateKey(keyPath.toString());
        }
        kpair.writePublicKey(pubKeyPath.toString(), System.getProperty("user.name") + "@localcomputer");
        kpair.dispose();
        loadLocalKey(pubKeyPath.toString(), holder);
    }

    public static void generateRemoteKeys(AtomicBoolean stopFlag, SessionInfo info, SshKeyHolder holder, String passPhrase) throws Exception {
        if (stopFlag.get()) {
            throw new Exception("Operation cancelled");
        }

        try (SshFileSystem fileSystem = new SshFileSystem(new SshModalUserInteraction(info))) {
            fileSystem.connect();
            String path1 = "$HOME/.ssh/id_rsa";
            String path = path1 + ".pub";

            String cmd = "ssh-keygen -q -N \"" + passPhrase + "\" -f \"" + path1
                    + "\"";

            try {
                fileSystem.getSftp().rm(path1);
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw new Exception(e);
                }
            }

            try {
                fileSystem.getSftp().rm(path);
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw new Exception(e);
                }
            }

            StringBuilder output = new StringBuilder();
            if (!SshCommandUtils.exec(fileSystem.getWrapper(), cmd, stopFlag, output)) {
                throw new Exception();
            }
            loadRemoteKeys(holder, fileSystem);
        }
    }

    private static String getPubKeyPath(SessionInfo info) {
        if (info.getPrivateKeyFile() != null) {
            String path = PathUtils.combine(PathUtils.getParent(info.getPrivateKeyFile()),
                    PathUtils.getFileName(info.getPrivateKeyFile()) + ".pub", File.separator);
            if (new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    public static void saveAuthorizedKeysFile(String authorizedKeys, SessionInfo info, AtomicBoolean stopFlag) throws Exception {
        try (SshFileSystem fileSystem = new SshFileSystem(new SshModalUserInteraction(info))) {
            fileSystem.connect();
            ChannelSftp sftp = fileSystem.getSftp();
            boolean found = false;
            try {
                Vector<?> list = sftp.ls(PathUtils.combineUnix(sftp.getHome(), ".ssh"));
                for (Object ent : list) {
                    ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) ent;
                    if (entry.getFilename().equals("authorized_keys")) {
                        found = true;
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!found) {
                sftp.mkdir(PathUtils.combineUnix(sftp.getHome(), ".ssh"));
            }
            try (OutputStream out = fileSystem.getSftp().put(PathUtils.combineUnix(fileSystem.getHome(), "/.ssh/authorized_keys"))) {
                out.write(authorizedKeys.getBytes("utf-8"));
            }
        }
    }
}
