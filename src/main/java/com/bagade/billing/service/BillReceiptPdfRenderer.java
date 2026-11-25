package com.bagade.billing.service;

import com.bagade.billing.dto.BillPrintCorrectionResponse;
import com.bagade.billing.dto.BillPrintLineResponse;
import com.bagade.billing.dto.BillPrintPaymentResponse;
import com.bagade.billing.dto.BillPrintResponse;
import com.bagade.billing.entity.BillStatus;
import com.bagade.billing.entity.PaymentDirection;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders an 80 mm thermal receipt profile PDF from a saved bill revision snapshot.
 */
@Component
public class BillReceiptPdfRenderer {

    private static final float WIDTH_80MM_PT = 226.77f;
    private static final float MARGIN_PT = 10f;
    private static final float LINE_HEIGHT_PT = 11f;
    private static final int MAX_CHARS_PER_LINE = 36;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public byte[] render80mmPdf(BillPrintResponse printData, ZoneId shopZone) {
        List<String> lines = buildReceiptLines(printData, shopZone);
        float height = Math.max(260f, (lines.size() * LINE_HEIGHT_PT) + (MARGIN_PT * 2f) + 12f);

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(WIDTH_80MM_PT, height));
            doc.addPage(page);

            PDType1Font monoRegular = new PDType1Font(Standard14Fonts.FontName.COURIER);
            PDType1Font monoBold = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = height - MARGIN_PT - LINE_HEIGHT_PT;
                for (int i = 0; i < lines.size(); i++) {
                    String text = sanitizeAscii(lines.get(i));
                    boolean bold = (i == 0 || text.startsWith("TOTAL:") || text.startsWith("BILL:"));
                    cs.beginText();
                    cs.setFont(bold ? monoBold : monoRegular, 8.5f);
                    cs.newLineAtOffset(MARGIN_PT, y);
                    cs.showText(text);
                    cs.endText();
                    y -= LINE_HEIGHT_PT;
                }
            }

            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to render 80mm receipt PDF", ex);
        }
    }

    private List<String> buildReceiptLines(BillPrintResponse data, ZoneId shopZone) {
        List<String> out = new ArrayList<>();
        String divider = "-".repeat(MAX_CHARS_PER_LINE);

        out.add(data.shopName());
        out.add(divider);
        out.add("BILL: " + data.billNumber() + " (Rev " + data.revisionNumber() + ")");
        if (data.status() != null && data.status() != BillStatus.ISSUED) {
            out.add("STATUS: " + data.status().name());
        }
        out.add("DATE: " + TIMESTAMP_FORMATTER.format(data.issuedAt().atZone(shopZone)));
        if (data.customerName() != null) {
            String custLine = "CUST: " + data.customerName();
            if (data.customerContact() != null) {
                custLine += " (" + data.customerContact() + ")";
            }
            wrapText(custLine, out);
        }
        out.add(divider);

        for (BillPrintLineResponse item : data.lines()) {
            String itemName = item.size() != null
                    ? item.productName() + " [" + item.size() + "]"
                    : item.productName();
            wrapText(itemName, out);
            out.add(formatLeftRight(
                    "  " + item.quantity() + " x " + item.unitSellingPrice(),
                    item.lineTotal()
            ));
            if (!"0.00".equals(item.discountAmount())) {
                out.add(formatLeftRight("  Discount", "-" + item.discountAmount()));
            }
            if (item.returnedQuantity() > 0) {
                out.add("  Returned Qty: " + item.returnedQuantity());
            }
        }

        out.add(divider);
        out.add(formatLeftRight("Subtotal:", data.subtotal()));
        out.add(formatLeftRight("Discount:", "-" + data.discountTotal()));
        out.add(formatLeftRight("TOTAL:", "INR " + data.total()));
        out.add(formatLeftRight("Paid:", "INR " + data.paid()));
        out.add(formatLeftRight("Balance Due:", "INR " + data.balanceDue()));

        if (data.payments() != null && !data.payments().isEmpty()) {
            out.add(divider);
            out.add("PAYMENTS:");
            for (BillPrintPaymentResponse payment : data.payments()) {
                String baseLabel = payment.reference() != null
                        ? payment.mode() + " (" + payment.reference() + ")"
                        : payment.mode().name();
                String label = payment.direction() == PaymentDirection.REFUND
                        ? "REFUND " + baseLabel
                        : baseLabel;
                String amountDisplay = payment.direction() == PaymentDirection.REFUND
                        ? "-" + payment.amount()
                        : payment.amount();
                out.add(formatLeftRight("  " + truncate(label, 22), amountDisplay));
            }
        }

        if (data.corrections() != null && !data.corrections().isEmpty()) {
            out.add(divider);
            out.add("CORRECTIONS:");
            for (BillPrintCorrectionResponse correction : data.corrections()) {
                String delta = correction.amountDelta() != null ? correction.amountDelta() : "0.00";
                out.add(formatLeftRight("  " + correction.eventType().name(), delta));
                if (correction.reason() != null) {
                    wrapText("    " + correction.reason(), out);
                }
            }
        }

        out.add(divider);
        out.add("Thank you for shopping!");
        return out;
    }

    private void wrapText(String text, List<String> out) {
        String remaining = text;
        while (remaining.length() > MAX_CHARS_PER_LINE) {
            out.add(remaining.substring(0, MAX_CHARS_PER_LINE));
            remaining = remaining.substring(MAX_CHARS_PER_LINE);
        }
        out.add(remaining);
    }

    private String formatLeftRight(String left, String right) {
        int spaces = Math.max(1, MAX_CHARS_PER_LINE - left.length() - right.length());
        return left + " ".repeat(spaces) + right;
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    private String sanitizeAscii(String text) {
        return text.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
