package qupath.ext.annotools

import javafx.application.Platform
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.geometry.Pos
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.extensions.QuPathExtension
import qupath.fx.dialogs.Dialogs
import qupath.lib.common.Version
import qupath.lib.gui.tools.IconFactory
import org.controlsfx.glyphfont.FontAwesome

class AnnoToolsExtension implements QuPathExtension {

    String name = "AnnoTools"
    String description = "Toolbar dropdown launcher for annoToolScripts"
    Version quPathVersion = Version.parse("v0.7.0")

    @Override
    void installExtension(QuPathGUI qupath) {

        // Extract bundled scripts from JAR to a temp folder
        def tempDir = new File( System.getProperty('java.io.tmpdir'), 'annotools-scripts' )
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
            menuButton.setTooltip( new Tooltip( 'Select an annotation-modifying tool to run' ) )

            // Icon for button
            def glyph_font = FontAwesome.Glyph.CUBES
            def glyph_size = 14
            def icon = IconFactory.createNode( glyph_font, glyph_size )
            menuButton.setGraphic( icon )

            def scriptFiles = tempDir.listFiles()?.findAll { File f ->
                f.isFile() && f.getName().toLowerCase().endsWith( '.groovy' )
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

                    item.setOnAction {
                        if (!qupath.getProject()) {
                            Dialogs.showWarningNotification( "No project open!", "Open a project to use tools." )
                            return
                        }

                        def runner = new Thread({
                            try {
                                qupath.runScript( file, null )
                            } catch ( Throwable t ) {
                                t.printStackTrace()
                            }
                        } as Runnable, "Run-${file.name}")

                        runner.setDaemon( true )
                        runner.start()
                    }

                    menuButton.getItems().add( item )
                }

                if (scriptFiles.size() > 1) {
                    menuButton.getItems().add( 0, new SeparatorMenuItem() )
                }
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

                    item.setOnAction {
                        if (!qupath.getProject()) {
                            Dialogs.showWarningNotification( "No project open!", "Open a project to use tools." )
                            return
                        }

                        def runner = new Thread({
                            try {
                                qupath.runScript( file, null )
                            } catch ( Throwable t ) {
                                t.printStackTrace()
                            }
                        } as Runnable, "Run-${file.name}")

                        runner.setDaemon( true )
                        runner.start()
                    }

                    extMenu.getItems().add( item )
                }
            }
        }
    }
}