package dyorgio.runtime.jinputhook.cleaner;

/**
 *
 * @author dyorgio
 */
class LinuxJInputCleaner extends JInputCleaner {

    @Override
    public void cleanup() throws Exception {
        cleanShutdowHooks();
    }

}
