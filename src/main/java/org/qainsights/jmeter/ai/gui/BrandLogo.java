package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.Icon;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BrandLogo {

    private static final Logger log = LoggerFactory.getLogger(BrandLogo.class);
    private static final String RESOURCE_PREFIX = "/org/qainsights/jmeter/ai/logos/";
    private static final int VECTOR_VIEW_SIZE = 24;
    private static final ConcurrentMap<LogoKey, Object> DIAGRAMS = new ConcurrentHashMap<>();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicLong RESOURCE_IDS = new AtomicLong();

    private BrandLogo() {
    }

    static Icon icon(String name, int size) {
        if (name == null || !name.matches("[a-z]+") || size <= 0) {
            return null;
        }
        Color foreground = ThemeColors.foreground();
        LogoKey key = new LogoKey(name, foreground.getRGB());
        Object diagram = DIAGRAMS.computeIfAbsent(key, BrandLogo::loadDiagram);
        return diagram == null ? null : new SvgIcon(diagram, size);
    }

    static Icon tile(String name, int tileSize, int logoSize) {
        if (tileSize <= 0) {
            return null;
        }
        Icon logo = name == null ? null : icon(name, logoSize);
        return new Icon() {
            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                if (!(graphics instanceof Graphics2D original)) {
                    return;
                }
                Graphics2D g = (Graphics2D) original.create();
                setQualityHints(g);
                int arc = tileSize / 2;
                g.setColor(ThemeColors.subtleSurface());
                g.fillRoundRect(x, y, tileSize, tileSize, arc, arc);
                g.setColor(ThemeColors.separator());
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(x, y, tileSize - 1, tileSize - 1, arc, arc);
                if (logo != null) {
                    logo.paintIcon(component, g,
                            x + (tileSize - logo.getIconWidth()) / 2,
                            y + (tileSize - logo.getIconHeight()) / 2);
                }
                g.dispose();
            }

            @Override
            public int getIconWidth() {
                return tileSize;
            }

            @Override
            public int getIconHeight() {
                return tileSize;
            }
        };
    }

    static String logoFor(ProviderSetupOption option) {
        if (option == null) {
            return null;
        }
        return switch (option) {
            case ANTHROPIC -> "anthropic";
            case OPENAI -> "openai";
            case GEMINI -> "gemini";
            case DEEPSEEK -> "deepseek";
            case GROK -> "grok";
            case META_MUSE -> "meta";
            case OPENROUTER -> "openrouter";
            case OLLAMA -> "ollama";
            case SMART_ROUTING -> logoForBackend(ProviderSetupDialog.configuredSmartBackend());
            case APPLE_ON_DEVICE -> "apple";
        };
    }

    static String logoForBackend(String smartBackend) {
        if (smartBackend == null) {
            return null;
        }
        return switch (smartBackend.toLowerCase(Locale.ROOT)) {
            case "typesafe" -> "typesafe";
            case "openai" -> "openai";
            case "openrouter" -> "openrouter";
            case "local" -> "gemma";
            case "ollama" -> "ollama";
            case "off" -> null;
            default -> null;
        };
    }

    private static Object loadDiagram(LogoKey key) {
        String resource = RESOURCE_PREFIX + key.name() + ".svg";
        try (InputStream stream = BrandLogo.class.getResourceAsStream(resource)) {
            if (stream == null) {
                logFailure(key.name(), null);
                return null;
            }
            String svg = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Color foreground = new Color(key.foreground(), true);
            String color = String.format(Locale.ROOT, "#%02X%02X%02X",
                    foreground.getRed(), foreground.getGreen(), foreground.getBlue());
            svg = svg.replace("currentColor", color);
            String uniqueName = "provider-logo/" + key.name() + "-"
                    + Integer.toHexString(key.foreground()) + "-"
                    + RESOURCE_IDS.incrementAndGet() + ".svg";
            Object diagram = SvgSupport.load(svg, uniqueName);
            if (diagram == null) {
                logFailure(key.name(), null);
            }
            return diagram;
        } catch (IOException | RuntimeException e) {
            logFailure(key.name(), e);
            return null;
        } catch (LinkageError e) {
            logFailure(key.name(), e);
            return null;
        }
    }

    private static void logFailure(String name, Throwable failure) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            if (failure == null) {
                log.debug("Provider logo unavailable: {}", name);
            } else {
                log.debug("Could not load provider logo: {}", name, failure);
            }
        }
    }

    private static void setQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private record LogoKey(String name, int foreground) {
    }

    private static final class SvgIcon implements Icon {

        private final Object diagram;
        private final int size;

        private SvgIcon(Object diagram, int size) {
            this.diagram = diagram;
            this.size = size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (!(graphics instanceof Graphics2D original)) {
                return;
            }
            Graphics2D g = (Graphics2D) original.create();
            setQualityHints(g);
            try {
                SvgSupport.render(diagram, g, x, y, size);
            } catch (Exception | LinkageError e) {
                logFailure("render", e);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static final class SvgSupport {

        private static final com.kitfox.svg.SVGUniverse UNIVERSE = new com.kitfox.svg.SVGUniverse();

        private static synchronized Object load(String source, String uniqueName) {
            URI uri = UNIVERSE.loadSVG(new StringReader(source), uniqueName);
            if (uri == null) {
                return null;
            }
            com.kitfox.svg.SVGDiagram diagram = UNIVERSE.getDiagram(uri);
            if (diagram != null) {
                diagram.setDeviceViewport(new Rectangle(
                        0, 0, VECTOR_VIEW_SIZE, VECTOR_VIEW_SIZE));
            }
            return diagram;
        }

        private static void render(Object value, Graphics2D graphics, int x, int y, int size)
                throws com.kitfox.svg.SVGException {
            com.kitfox.svg.SVGDiagram diagram = (com.kitfox.svg.SVGDiagram) value;
            synchronized (diagram) {
                Rectangle2D view = diagram.getViewRect();
                if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
                    float width = diagram.getWidth();
                    float height = diagram.getHeight();
                    if (width <= 0 || height <= 0) {
                        throw new IllegalStateException("Provider logo has no renderable view box");
                    }
                    view = new Rectangle2D.Float(0, 0, width, height);
                }
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.translate(x, y);
                    g.scale(size / view.getWidth(), size / view.getHeight());
                    g.translate(-view.getX(), -view.getY());
                    diagram.render(g);
                } finally {
                    g.dispose();
                }
            }
        }
    }
}
