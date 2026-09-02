package qupath.ext.annotools

import javafx.application.Platform
import javafx.scene.Cursor
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.geometry.Pos
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.extensions.QuPathExtension
import qupath.fx.dialogs.Dialogs
import qupath.lib.common.Version
import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome
import java.util.concurrent.atomic.AtomicBoolean

class AnnoToolsExtension implements QuPathExtension {

    String name = "AnnoTools"
    String description = "Toolbar dropdown launcher for annoToolScripts"
    Version quPathVersion = Version.parse("v0.7.0")

    // Runs a tool script with visible "loading" feedback. Some scripts take a
    // moment to build their UI before showing a window, and until now that gap
    // had no indication beyond a WAIT cursor — QuPath stayed fully interactive,
    // so users could click around mid-load. This blocks the main window and
    // posts a notification immediately, then unblocks as soon as either the
    // script's own window steals focus, or the script thread finishes (covers
    // scripts that error out or never open a window at all).
    private static void runToolScript( QuPathGUI qupath, File file, String label ) {
        if (!qupath.getProject()) {
            Dialogs.showWarningNotification( "No project open!", "Open a project to use tools." )
            return
        }

        def stage = qupath.getStage()
        def scene = stage.getScene()
        def root = scene.getRoot()

        Dialogs.showPlainNotification( label, "Loading…" )

        def resumed = new AtomicBoolean( false )
        def resumeInteraction = {
            if (resumed.compareAndSet( false, true )) {
                Platform.runLater {
                    scene.setCursor( Cursor.DEFAULT )
                    root.setDisable( false )
                }
            }
        }

        Platform.runLater {
            scene.setCursor( Cursor.WAIT )
            root.setDisable( true )
        }

        // Resume as soon as the script's UI appears (main stage loses focus)
        def listener
        listener = { obs, oldVal, newVal ->
            if (!newVal) {
                resumeInteraction()
                stage.focusedProperty().removeListener( listener )
            }
        } as javafx.beans.value.ChangeListener
        stage.focusedProperty().addListener( listener )

        def runner = new Thread({
            try {
                qupath.runScript( file, null )
            } catch ( Throwable t ) {
                t.printStackTrace()
            } finally {
                // Catches scripts that error before showing a window, or that
                // never open one (e.g. run to completion headlessly)
                resumeInteraction()
                Platform.runLater { stage.focusedProperty().removeListener( listener ) }
            }
        } as Runnable, "Run-${file.name}")

        runner.setDaemon( true )
        runner.start()
    }

    @Override
    void installExtension(QuPathGUI qupath) {

        // Extract bundled scripts from JAR to a temp folder
        def tempDir = new File( System.getProperty('java.io.tmpdir'), 'annotools-scripts' )
        if (tempDir.exists()) { tempDir.deleteDir() }
        tempDir.mkdirs()

        try {
            def scriptNames = []
            def listUrl = getClass().getResource('/scripts/')
            if (listUrl != null) {
                def uri = listUrl.toURI()
                if (uri.scheme == 'jar') {
                    // Running from JAR — walk the JAR entries
                    def jarPath = uri.toString().split('!')[0].replace('jar:', '')
                    def jar = new java.util.jar.JarFile( new java.net.URI(jarPath).toURL().file )
                    scriptNames = jar.entries().findAll { entry ->
                        entry.name.startsWith('scripts/') && entry.name.endsWith('.groovy')
                    }.collect { it.name.tokenize('/').last() }
                    jar.close()
                } else {
                    // Running from filesystem (e.g. during dev)
                    scriptNames = new File(uri).listFiles()?.findAll {
                        it.name.endsWith('.groovy')
                    }?.collect { it.name } ?: []
                }

                // Write out all existing scripts to temp dir to be accessed by the menu
                scriptNames.each { name ->
                    def stream = getClass().getResourceAsStream( "/scripts/${name}" )
                    def outFile = new File( tempDir, name )
                    outFile.bytes = stream.bytes
                    stream.close()
                }
            }
        } catch (Exception e) {
            System.out.println( "AnnoTools: Failed to extract bundled scripts — ${e.message}" )
            return
        }

        // Unique marker so we can avoid adding duplicates
        def markerKey = 'scriptFolderDropdownButton'

        Platform.runLater {
            def toolbar = qupath.getToolBar()

            // Remove any previous copy of this button
            def oldButtons = toolbar.getItems().findAll { node ->
                node?.getProperties()?.get( markerKey ) == true
            }
            toolbar.getItems().removeAll( oldButtons )

            def menuButton = new MenuButton()
            menuButton.getProperties().put( markerKey, true )
            menuButton.setFocusTraversable( false )
            menuButton.setTooltip( new Tooltip( 'AnnoTools' ) )

            // Icon for button
            def glyph_font = FontAwesome.Glyph.CUBES
            def glyph_size = 14
            def icon = IconFactory.createNode( glyph_font, glyph_size )
            menuButton.setGraphic( icon )

            def scriptFiles = tempDir.listFiles()?.findAll { File f ->
                f.isFile() && f.getName().toLowerCase().endsWith( '.groovy' )
            }?.sort { File f ->
                def orderLine = f.readLines().reverse().find { it =~ /^\/\/\s*ORDER:\s*(\d+)/ }
                if (orderLine) {
                    def matcher = orderLine =~ /^\/\/\s*ORDER:\s*(\d+)/
                    return matcher[0][1].toInteger()
                }
                return Integer.MAX_VALUE
            }

            if (!scriptFiles || scriptFiles.isEmpty()) {
                def emptyItem = new MenuItem( '(No .groovy scripts found)' )
                emptyItem.setDisable( true )
                menuButton.getItems().add( emptyItem )
            } else {
                scriptFiles.each { File file ->
                    def label = file.getName().replace( "-", " " ).replace( ".groovy", "" )
                    def item = new MenuItem()

                    def graphic = new Label( label )
                    graphic.setMaxWidth( Double.MAX_VALUE )

                    try {
                        def lines = file.readLines()
                        // Tooltip from last line
                        if (lines.last()?.startsWith( '//' )) {
                            graphic.setTooltip( new Tooltip( lines.last().replaceFirst(/^\/\/\s*/, '') ) )
                        }
                        // Icon — scan from bottom for // ICON: line
                        def iconLine = lines.reverse().find { it =~ /^\/\/\s*ICON:\s*(\w+)/ }
                        if (iconLine) {
                            def matcher = iconLine =~ /^\/\/\s*ICON:\s*(\w+)/
                            def glyphName = matcher[0][1].trim()
                            try {
                                def glyph = FontAwesome.Glyph.valueOf( glyphName )
                                def iconNode = IconFactory.createNode( glyph, 14 )
                                def hbox = new HBox( 6, iconNode, graphic )
                                hbox.setAlignment( Pos.CENTER_LEFT )
                                item.setGraphic( hbox )
                            } catch (IllegalArgumentException ignored) {
                                item.setGraphic( graphic )
                            }
                        } else {
                            item.setGraphic( graphic )
                        }
                    } catch (Exception e) {
                        item.setGraphic( graphic )
                    }

                    item.setOnAction { runToolScript( qupath, file, label ) }

                    menuButton.getItems().add( item )
                }
            }

            // Popups resolve looked-up colours through their owner node, so if the toolbar
            // overflows (menu button moved into the overflow menu) the item labels inherit
            // '-fx-background' from the highlighted outer menu item and render dark.
            // Pin it to the theme base so the text ladder stays correct in light and dark.
            menuButton.setOnShowing {
                def popup = menuButton.getItems()*.getParentPopup().find { it != null }
                popup?.setStyle( '-fx-background: -fx-base;' )
            }

            toolbar.getItems().add( menuButton )

            
            // Extensions menu
            def extMenu = qupath.getMenu( 'Extensions>AnnoTools', true )
            extMenu.getItems().clear()

            if (!scriptFiles || scriptFiles.isEmpty()) {
                def emptyItem = new MenuItem( '(No .groovy scripts found)' )
                emptyItem.setDisable( true )
                extMenu.getItems().add( emptyItem )
            } else {
                scriptFiles.each { File file ->
                    def label = file.getName().replace( "-", " " ).replace( ".groovy", "" )
                    def item = new MenuItem( label )

                    item.setOnAction { runToolScript( qupath, file, label ) }

                    extMenu.getItems().add( item )
                }
            }
        }
    }
}