package network.tos.wallet;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK only: public import fixture and ciphertext export destination via the system picker. */
public final class PublicBackupDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    private File exports() throws FileNotFoundException {
        File directory = new File(getContext().getCacheDir(), "encrypted-exports");
        if (!directory.isDirectory() && !directory.mkdir()) throw new FileNotFoundException();
        return directory;
    }
    private File exported(String id) throws FileNotFoundException {
        if (!id.matches("saved-[0-9a-f-]{36}")) throw new FileNotFoundException();
        File file = new File(exports(), id);
        if (!file.isFile()) throw new FileNotFoundException();
        return file;
    }
    @Override public synchronized String createDocument(String parent, String mime, String displayName) throws FileNotFoundException {
        if (!"root".equals(parent) || !"application/octet-stream".equals(mime)) throw new FileNotFoundException();
        File[] existing = exports().listFiles();
        if (existing == null || existing.length >= 8) throw new FileNotFoundException();
        String id = "saved-" + java.util.UUID.randomUUID();
        try {
            if (!new File(exports(), id).createNewFile()) throw new IOException();
            // Public document identifier only; no password or plaintext is stored by this provider.
            try (OutputStream output = new FileOutputStream(new File(getContext().getCacheDir(), "last-created-backup.txt"))) {
                output.write(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
        } catch (IOException error) { throw new FileNotFoundException("Cannot create encrypted export destination"); }
        getContext().sendBroadcast(new android.content.Intent("network.tos.wallet.test.EXPORT_DOCUMENT_CREATED")
            .setPackage("network.tos.wallet").putExtra("documentId", id));
        return id;
    }
    private synchronized File file() throws FileNotFoundException {
        File file = new File(getContext().getCacheDir(), "ios-public-mixed-role-v1.tosqbk");
        if (!file.exists()) {
            try (InputStream input = getContext().getAssets().open(file.getName());
                 OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } catch (IOException error) {
                file.delete();
                throw new FileNotFoundException("Public backup fixture unavailable");
            }
        }
        return file;
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES });
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "public-backups").add(Root.COLUMN_DOCUMENT_ID, "root")
            .add(Root.COLUMN_TITLE, "Public TOS backup fixtures").add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE)
            .add(Root.COLUMN_MIME_TYPES, "application/octet-stream");
        return cursor;
    }
    private MatrixCursor documents(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE });
    }
    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        if ("root".equals(id)) cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, "Public TOS backup fixtures")
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
        else if ("ios-public".equals(id)) cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, "Public-iOS-backup.tosqbk")
            .add(Document.COLUMN_MIME_TYPE, "application/octet-stream").add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, file().length());
        else cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, "Encrypted-wallet-backup.tosqbk")
            .add(Document.COLUMN_MIME_TYPE, "application/octet-stream")
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE).add(Document.COLUMN_SIZE, exported(id).length());
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = documents(projection); row(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException();
        MatrixCursor cursor = documents(projection); row(cursor, "ios-public");
        File[] files = exports().listFiles();
        if (files == null || files.length > 8) throw new FileNotFoundException();
        for (File file : files) row(cursor, file.getName());
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        if ("ios-public".equals(id) && "r".equals(mode))
            return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY);
        if (!"r".equals(mode) && !"wt".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(exported(id), "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY :
            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
    }
}
