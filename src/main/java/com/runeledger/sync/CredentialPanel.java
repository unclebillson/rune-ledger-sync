package com.runeledger.sync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import net.runelite.client.ui.PluginPanel;

final class CredentialPanel extends PluginPanel
{
    private final JPasswordField code = new JPasswordField();
    private final JButton save = new JButton("Use code");
    private final JButton clear = new JButton("Clear code");
    private final JLabel destination = new JLabel("No character selected.");
    private final JLabel status = new JLabel(" ");
    private CredentialScope selected;
    private boolean busy;

    CredentialPanel(Consumer<char[]> onSave, Runnable onClear)
    {
        setLayout(new BorderLayout(0, 8));
        JPanel form = new JPanel(new BorderLayout(0, 6));
        form.add(new JLabel("<html>Log in on the paired character. Select Local app or set your hosted URL in the plugin settings.</html>"),
            BorderLayout.NORTH);
        code.getAccessibleContext().setAccessibleName("Rune Ledger sync code");
        code.putClientProperty("JPasswordField.cutCopyAllowed", false);
        form.add(code, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        buttons.add(save);
        buttons.add(clear);
        form.add(buttons, BorderLayout.SOUTH);
        add(form, BorderLayout.NORTH);
        JPanel messages = new JPanel();
        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.add(destination);
        messages.add(status);
        add(messages, BorderLayout.CENTER);

        save.addActionListener(event ->
        {
            char[] password = code.getPassword();
            code.setText("");
            setBusy(true);
            onSave.accept(password);
        });
        code.addActionListener(event -> save.doClick());
        clear.addActionListener(event ->
        {
            code.setText("");
            setBusy(true);
            onClear.run();
        });
        setBusy(false);
    }

    void showScope(CredentialScope scope)
    {
        selected = scope;
        code.setText("");
        status.setText(" ");
        if (scope == null)
        {
            destination.setText("No character selected.");
        }
        else
        {
            String endpoint = scope.hostedEndpoint == null ? "Local app" : scope.hostedEndpoint.getAuthority();
            destination.setText("<html>" + scope.player + " · " + endpoint + "</html>");
        }
        setBusy(busy);
    }

    CredentialScope selectedScope()
    {
        return selected;
    }

    void finish(String message)
    {
        status.setText("<html>" + message + "</html>");
        setBusy(false);
    }

    private void setBusy(boolean busy)
    {
        this.busy = busy;
        boolean enabled = !busy && selected != null;
        code.setEnabled(enabled);
        save.setEnabled(enabled);
        clear.setEnabled(enabled);
    }

    static BufferedImage icon()
    {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setColor(new Color(0x93CAA5));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            graphics.drawString("RL", 2, 14);
            return image;
        }
        finally
        {
            graphics.dispose();
        }
    }
}
